package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.library.dto.LibraryChangeEvent;
import de.oppahansi.kosmos.library.naming.NamingContext;
import de.oppahansi.kosmos.library.naming.NamingSettings;
import de.oppahansi.kosmos.library.naming.NamingSettingsService;
import de.oppahansi.kosmos.library.naming.NamingTemplateEngine;
import de.oppahansi.kosmos.media.AnimeEpisode;
import de.oppahansi.kosmos.media.Episode;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.anidb.AniDbUdpClient;
import de.oppahansi.kosmos.notifications.NotificationEvent;
import de.oppahansi.kosmos.notifications.NotificationEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Imports one already-known video file (see {@link VideoFileScanner} for how a directory resolves
 * to one) into the library: hardlink first so the source torrent keeps seeding untouched, falling
 * back to copy only when source and target aren't on the same filesystem; write under a temp name
 * and atomically rename into place.
 */
@ApplicationScoped
public class ImportService {

  @Inject LibraryRootFolderService rootFolderService;
  @Inject ProbeService probeService;
  @Inject AniDbUdpClient aniDbUdpClient;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject Event<NotificationEvent> notificationEvent;
  @Inject NamingSettingsService namingSettingsService;
  @Inject LibraryChangeBroadcaster libraryChangeBroadcaster;

  private final NamingTemplateEngine namingTemplateEngine = new NamingTemplateEngine();

  @Transactional
  public LibraryFile importPath(MediaItem mediaItem, String sourcePathRaw) {
    Path source = pickVideoFile(Path.of(sourcePathRaw));
    Path target = targetPathFor(mediaItem, source);

    if (LibraryFile.find("path", target.toString()).firstResultOptional().isPresent()) {
      throw new BadRequestException("Already imported at " + target);
    }

    materialize(source, target);

    LibraryFile file = new LibraryFile();
    file.mediaItem = mediaItem;
    file.path = target.toString();
    try {
      file.sizeBytes = Files.size(target);
    } catch (IOException e) {
      throw new InternalServerErrorException(
          "Imported file vanished immediately after import: " + target);
    }
    file.matchMethod = "GRAB";
    file.matchConfidence = 1.0f;
    file.matchPinned = false;
    file.matchedAt = Instant.now();
    file.verified = false;
    file.importedAt = Instant.now();
    probeService.tryProbe(file);
    file.persist();
    tryHash(file, target);
    String title = displayTitleFor(mediaItem);
    notificationEvent.fire(
        new NotificationEvent(
            NotificationEventType.IMPORT,
            mediaItem.id,
            title,
            title + " has been imported and is ready to watch.",
            file.sizeBytes));
    libraryChangeBroadcaster.publish(new LibraryChangeEvent(mediaItem.contentType, mediaItem.id));
    return file;
  }

  /**
   * {@code mediaItem.title} alone is the episode's own title for an episode/anime episode — not
   * very identifiable without the show/anime it belongs to, so this prefixes that on for those two
   * content types. A movie's own title already stands on its own.
   */
  private String displayTitleFor(MediaItem mediaItem) {
    return switch (mediaItem.contentType) {
      case "episode" ->
          Episode.<Episode>findByIdOptional(mediaItem.id)
              .map(e -> e.season.show.mediaItem.title + " - " + mediaItem.title)
              .orElse(mediaItem.title);
      case "anime_episode" ->
          AnimeEpisode.<AnimeEpisode>findByIdOptional(mediaItem.id)
              .map(e -> e.season.anime.mediaItem.title + " - " + mediaItem.title)
              .orElse(mediaItem.title);
      default ->
          mediaItem.year == null ? mediaItem.title : mediaItem.title + " (" + mediaItem.year + ")";
    };
  }

  /**
   * Best-effort, same reasoning as {@link ProbeService#tryProbe} — a hashing failure (an unreadable
   * file, a full disk mid-read) shouldn't undo an otherwise-successful import. Every import gets
   * both hashes computed regardless of content type, since only the AniDB lookup that follows is
   * anime-specific, not the hashing itself.
   */
  private void tryHash(LibraryFile file, Path target) {
    String ed2k;
    try {
      persistHash(file, "CRC32", HashService.computeCrc32(target));
      ed2k = HashService.computeEd2k(target);
      persistHash(file, "ED2K", ed2k);
    } catch (IOException e) {
      // Left unhashed; nothing currently retries this, same gap as an unprobed file.
      return;
    }
    tryAniDbLookup(file, ed2k);
  }

  /**
   * AniDB hash identification — only attempted for anime episodes, since that's the one content
   * type AniDB indexes, and only when credentials are configured; skipped entirely otherwise rather
   * than throwing. A match upgrades {@code matchMethod} to record that this file's identity is
   * hash-verified rather than just inherited from whatever it was grabbed against, and links the
   * episode's own {@link MediaItem} to its AniDB episode id — {@code AnimeService} links the
   * series-level id separately, from Fribb's cross-reference data.
   */
  private void tryAniDbLookup(LibraryFile file, String ed2kHex) {
    if (!"anime_episode".equals(file.mediaItem.contentType) || !aniDbUdpClient.isConfigured()) {
      return;
    }
    try {
      aniDbUdpClient
          .lookupByHash(file.sizeBytes, ed2kHex)
          .ifPresent(
              match -> {
                externalIdLinkService.link(file.mediaItem, "anidb", match.episodeId());
                file.matchMethod = "ANIDB_HASH";
              });
    } catch (RuntimeException e) {
      // Best-effort — a lookup/ban/network failure shouldn't undo an otherwise-successful import.
    }
  }

  private void persistHash(LibraryFile file, String algorithm, String value) {
    LibraryFileHash hash = new LibraryFileHash();
    hash.libraryFile = file;
    hash.algorithm = algorithm;
    hash.value = value;
    hash.persist();
  }

  /**
   * Resolves a source path to the single video file to import — itself if a file, or the largest
   * non-sample video file if a directory.
   */
  private Path pickVideoFile(Path source) {
    return VideoFileScanner.largest(source);
  }

  /**
   * What {@link #importPath} would name this file under the current naming settings — Preview
   * Rename's own backing call ({@code RefreshScanService}/{@code LibraryFileResource}), reusing the
   * exact same template-rendering path a real import already goes through rather than a second
   * implementation of it. {@code currentPathRaw} is only read for its extension.
   */
  public Path previewTargetPath(MediaItem mediaItem, String currentPathRaw) {
    return targetPathFor(mediaItem, Path.of(currentPathRaw));
  }

  /**
   * Movies land directly under their own folder; episodes nest under their show/anime's own folder
   * instead of one named after the episode itself — a season subfolder for TV (matching Sonarr),
   * flat for anime, which has no season subfolder of its own yet. The exact shape of every folder
   * and file name comes from {@link NamingSettingsService}/{@link NamingTemplateEngine} — see the
   * former for the default templates this behaves as if hardcoded to when nobody has customized
   * them.
   */
  private Path targetPathFor(MediaItem mediaItem, Path source) {
    LibraryRootFolder rootFolder =
        mediaItem.rootFolder != null
            ? mediaItem.rootFolder
            : rootFolderService
                .getDefault(mediaItem.contentType)
                .orElseThrow(
                    () -> new InternalServerErrorException("No library root folder is configured"));
    String extension = extensionOf(source);

    return switch (mediaItem.contentType) {
      case "episode" -> episodeTargetPath(rootFolder, mediaItem, extension);
      case "anime_episode" -> animeEpisodeTargetPath(rootFolder, mediaItem, extension);
      default -> {
        NamingSettings settings = namingSettingsService.forContentType("movie");
        NamingContext context = NamingContext.forMovie(mediaItem.title, mediaItem.year);
        String fileName = namingTemplateEngine.render(settings.fileTemplate, context);
        yield movieFolder(mediaItem, rootFolder).resolve(fileName + extension);
      }
    };
  }

  /**
   * The folder a movie's own file(s) are organized under — split out from {@link #targetPathFor}
   * since {@code RefreshScanService}'s rescan needs to list this folder's contents without already
   * knowing a specific source file (and so, unlike {@link #targetPathFor}, without an extension).
   */
  public Path movieFolder(MediaItem mediaItem) {
    LibraryRootFolder rootFolder =
        mediaItem.rootFolder != null
            ? mediaItem.rootFolder
            : rootFolderService
                .getDefault(mediaItem.contentType)
                .orElseThrow(
                    () -> new InternalServerErrorException("No library root folder is configured"));
    return movieFolder(mediaItem, rootFolder);
  }

  private Path movieFolder(MediaItem mediaItem, LibraryRootFolder rootFolder) {
    NamingSettings settings = namingSettingsService.forContentType("movie");
    NamingContext context = NamingContext.forMovie(mediaItem.title, mediaItem.year);
    String folderName = namingTemplateEngine.render(settings.folderTemplate, context);
    return Path.of(rootFolder.path, folderName);
  }

  private Path episodeTargetPath(
      LibraryRootFolder rootFolder, MediaItem mediaItem, String extension) {
    Episode episode =
        Episode.<Episode>findByIdOptional(mediaItem.id)
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        "No episode row for media item " + mediaItem.id));
    MediaItem showMediaItem = episode.season.show.mediaItem;

    NamingSettings settings = namingSettingsService.forContentType("show");
    NamingContext seriesContext = NamingContext.forShow(showMediaItem.title, showMediaItem.year);
    NamingContext episodeContext =
        NamingContext.forEpisode(
            showMediaItem.title,
            showMediaItem.year,
            mediaItem.title,
            episode.season.seasonNumber,
            episode.episodeNumber);

    String seriesFolder = namingTemplateEngine.render(settings.folderTemplate, seriesContext);
    String fileName = namingTemplateEngine.render(settings.fileTemplate, episodeContext);
    // null (the default) defers to the global show naming setting; an explicit per-show false
    // skips the season subfolder regardless of it — see Show#seasonFolderEnabled.
    boolean useSeasonFolder =
        Boolean.TRUE.equals(episode.season.show.seasonFolderEnabled)
            || episode.season.show.seasonFolderEnabled == null;
    if (!useSeasonFolder) {
      return Path.of(rootFolder.path, seriesFolder, fileName + extension);
    }
    String seasonFolder =
        namingTemplateEngine.render(settings.seasonFolderTemplate, episodeContext);
    return Path.of(rootFolder.path, seriesFolder, seasonFolder, fileName + extension);
  }

  private Path animeEpisodeTargetPath(
      LibraryRootFolder rootFolder, MediaItem mediaItem, String extension) {
    AnimeEpisode animeEpisode =
        AnimeEpisode.<AnimeEpisode>findByIdOptional(mediaItem.id)
            .orElseThrow(
                () ->
                    new InternalServerErrorException(
                        "No anime episode row for media item " + mediaItem.id));
    MediaItem animeMediaItem = animeEpisode.season.anime.mediaItem;
    Integer number =
        animeEpisode.absoluteEpisodeNumber != null
            ? animeEpisode.absoluteEpisodeNumber
            : animeEpisode.episodeNumber;

    NamingSettings settings = namingSettingsService.forContentType("anime");
    NamingContext animeContext = NamingContext.forShow(animeMediaItem.title, animeMediaItem.year);
    NamingContext episodeContext =
        NamingContext.forAnimeEpisode(
            animeMediaItem.title, animeMediaItem.year, mediaItem.title, number);

    String animeFolder = namingTemplateEngine.render(settings.folderTemplate, animeContext);
    String fileName = namingTemplateEngine.render(settings.fileTemplate, episodeContext);
    return Path.of(rootFolder.path, animeFolder, fileName + extension);
  }

  private String extensionOf(Path source) {
    String sourceName = source.getFileName().toString();
    int dot = sourceName.lastIndexOf('.');
    return dot >= 0 ? sourceName.substring(dot) : "";
  }

  /**
   * Hardlinks source into a temp name beside target, falling back to copy across filesystems, then
   * atomically renames into place.
   */
  private void materialize(Path source, Path target) {
    try {
      Files.createDirectories(target.getParent());
      Path temp = target.resolveSibling(target.getFileName() + ".importing");
      Files.deleteIfExists(temp);

      try {
        Files.createLink(temp, source);
      } catch (IOException e) {
        // Not on the same filesystem (or hardlinks unsupported here) — fall back to copy.
        Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
      }

      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new InternalServerErrorException(
          "Failed to import " + source + " -> " + target + ": " + e.getMessage());
    }
  }
}

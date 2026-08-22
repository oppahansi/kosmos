package de.oppahansi.kosmos.sonarr;

import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.library.LibraryRootFolder;
import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.library.ProbeService;
import de.oppahansi.kosmos.media.Episode;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.media.Show;
import de.oppahansi.kosmos.media.ShowService;
import de.oppahansi.kosmos.metadata.MediaItemExternalId;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import de.oppahansi.kosmos.sonarr.dto.SonarrLibrarySyncResult;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports an already-managed Sonarr library into Kosmos as {@link Show}s. Unlike {@code
 * JellyfinSyncService}, this deliberately does not auto-classify a series as {@link
 * de.oppahansi.kosmos.media.Anime} — that classification (Fribb/AniList cross-reference, confidence
 * scoring, an {@code UnclassifiedShow} review queue) is substantial machinery already proven for
 * Jellyfin sync; a user who wants Sonarr-sourced anime auto-classified can run Jellyfin sync
 * against the same library, or reclassify manually. A Sonarr series whose TMDB id is already known
 * to Kosmos as an {@code Anime} is still recognized (never duplicated as a separate Show) — see
 * {@link #syncOneShow} — just not promoted the other way.
 *
 * <p>Sonarr's series resource carries its own {@code tmdbId} directly for most titles today; {@link
 * #resolveTmdbId} falls back to {@link TmdbMetadataProvider#findTvIdByTvdbId} (via Sonarr's
 * always-present {@code tvdbId}) only for the remainder. Only series with a resolvable TMDB id and
 * a reported path are imported; episode files are matched the same way {@code
 * JellyfinSyncService#linkEpisodeFiles} does, by (season, episode) number against Kosmos's own
 * TMDB-built episode tree.
 *
 * <p>Every series gets its own transaction — same reasoning as {@code JellyfinSyncService}: one bad
 * item must never roll back everything else already synced in the same run.
 */
@ApplicationScoped
public class SonarrSyncService {

  private static final String TMDB_PLUGIN_SLUG = "tmdb";
  private static final String MATCH_METHOD = "SONARR_SYNC";

  /** Same reasoning as {@code JellyfinSyncService} — release year is an absolute fact. */
  private static final int YEAR_TOLERANCE = 0;

  @Inject ProbeService probeService;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject ShowService showService;
  @Inject SonarrServerService serverService;

  public SonarrLibrarySyncResult syncLibrary(UUID serverId, ProgressReporter progress) {
    SonarrServer server = requireServer(serverId);
    // Same auto-register the Jellyfin flow does inline — otherwise newly-synced shows have
    // nowhere registered to be organized under.
    serverService.autoRegisterRootFolders(serverId);
    SonarrClient client = new SonarrClient(server.baseUrl, server.apiKey);
    List<SonarrSeries> seriesList;
    try {
      seriesList = client.listSeries();
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Sonarr server: " + e.getMessage());
    }

    int total = seriesList.size();
    int processed = 0;
    int linked = 0;
    int created = 0;
    int alreadySynced = 0;
    int skippedNoId = 0;
    int episodeFilesLinked = 0;

    for (SonarrSeries series : seriesList) {
      processed++;
      progress.update(processed, total, series.title());
      String tmdbId = resolveTmdbId(series);
      if (tmdbId == null || series.path() == null) {
        skippedNoId++;
        continue;
      }
      List<SonarrEpisode> episodes;
      try {
        episodes = client.listEpisodes(series.id());
      } catch (IOException | InterruptedException e) {
        skippedNoId++; // couldn't fetch this series' own episodes; skip rather than fail the run
        continue;
      }
      try {
        ShowSyncOutcome outcome =
            QuarkusTransaction.requiringNew().call(() -> syncOneShow(series, tmdbId, episodes));
        switch (outcome.outcome()) {
          case "linked" -> linked++;
          case "created" -> created++;
          default -> alreadySynced++;
        }
        episodeFilesLinked += outcome.episodeFilesLinked();
      } catch (RuntimeException e) {
        alreadySynced++; // most likely a race, or this show's TMDB fetch failed; skip for now
      }
    }

    progress.update(total, total, "Done");
    return new SonarrLibrarySyncResult(
        total, linked, created, alreadySynced, skippedNoId, episodeFilesLinked);
  }

  private String resolveTmdbId(SonarrSeries series) {
    if (series.tmdbId() != null) {
      return series.tmdbId();
    }
    if (series.tvdbId() == null) {
      return null;
    }
    return tmdbMetadataProvider.findTvIdByTvdbId(series.tvdbId()).orElse(null);
  }

  private SonarrServer requireServer(UUID serverId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                SonarrServer.<SonarrServer>findByIdOptional(serverId)
                    .orElseThrow(
                        () -> new BadRequestException("Unknown Sonarr server id: " + serverId)));
  }

  private record ShowSyncOutcome(String outcome, int episodeFilesLinked) {}

  /**
   * @return "linked", "created", or "already-synced" (mirrors {@code
   *     JellyfinSyncService#syncOneShow}) — plus how many new episode files got matched
   */
  private ShowSyncOutcome syncOneShow(
      SonarrSeries series, String tmdbId, List<SonarrEpisode> episodes) {
    // A title already synced (e.g. via Jellyfin) as Anime keeps that identity — Sonarr import
    // never auto-classifies anime (see this class's own doc), but it must not duplicate a title
    // that's already correctly represented under the other content type either.
    if (resolveExisting(tmdbId, "anime", series.year()).isPresent()) {
      return new ShowSyncOutcome("already-synced", 0);
    }

    Optional<MediaItem> existing = resolveExisting(tmdbId, "show", series.year());
    MediaItem mediaItem;
    boolean createdNow;
    if (existing.isPresent()) {
      mediaItem = existing.get();
      createdNow = false;
      if (mediaItem.rootFolder == null) {
        resolveRootFolder(series.path()).ifPresent(folder -> mediaItem.rootFolder = folder);
      }
    } else {
      LibraryRootFolder rootFolder = resolveRootFolder(series.path()).orElse(null);
      Show show =
          showService.createFromExternalSource(series.title(), series.year(), tmdbId, rootFolder);
      mediaItem = show.mediaItem;
      createdNow = true;
    }

    int linkedFiles = linkEpisodeFiles(mediaItem, episodes);
    String outcome = createdNow ? "created" : (linkedFiles > 0 ? "linked" : "already-synced");
    return new ShowSyncOutcome(outcome, linkedFiles);
  }

  /** Same idea as {@code JellyfinSyncService#resolveExistingMediaItem} — see its own doc. */
  private Optional<MediaItem> resolveExisting(String tmdbId, String contentType, Integer year) {
    List<MediaItemExternalId> links =
        MediaItemExternalId.list(
            "plugin.slug = ?1 and externalId = ?2 and mediaItem.contentType = ?3"
                + " and supersededAt is null",
            TMDB_PLUGIN_SLUG,
            tmdbId,
            contentType);
    for (MediaItemExternalId link : links) {
      Integer existingYear = link.mediaItem.year;
      if (year == null || existingYear == null || Math.abs(year - existingYear) <= YEAR_TOLERANCE) {
        return Optional.of(link.mediaItem);
      }
    }
    return Optional.empty();
  }

  private Optional<LibraryRootFolder> resolveRootFolder(String path) {
    Optional<LibraryRootFolder> containing = rootFolderService.findContaining(path);
    return containing.isPresent() ? containing : rootFolderService.getDefault("show");
  }

  /** Same idea as {@code JellyfinSyncService#linkEpisodeFiles} — see its own doc. */
  private int linkEpisodeFiles(MediaItem showMediaItem, List<SonarrEpisode> sonarrEpisodes) {
    if (sonarrEpisodes.isEmpty()) {
      return 0;
    }
    Show show = Show.<Show>findByIdOptional(showMediaItem.id).orElse(null);
    if (show == null) {
      return 0;
    }

    int linked = 0;
    for (SonarrEpisode sonarrEpisode : sonarrEpisodes) {
      if (sonarrEpisode.seasonNumber() == null
          || sonarrEpisode.episodeNumber() == null
          || sonarrEpisode.path() == null) {
        continue;
      }
      Optional<Episode> episode =
          Episode.<Episode>find(
                  "season.show = ?1 and season.seasonNumber = ?2 and episodeNumber = ?3",
                  show,
                  sonarrEpisode.seasonNumber(),
                  sonarrEpisode.episodeNumber())
              .firstResultOptional();
      if (episode.isEmpty()) {
        continue;
      }
      if (LibraryFile.find("path", sonarrEpisode.path()).firstResultOptional().isPresent()) {
        continue;
      }

      LibraryFile file = new LibraryFile();
      file.mediaItem = episode.get().mediaItem;
      file.path = sonarrEpisode.path();
      file.sizeBytes = sizeOrZero(sonarrEpisode.path());
      file.matchMethod = MATCH_METHOD;
      file.matchConfidence = 1.0f;
      file.matchPinned = false;
      file.matchedAt = Instant.now();
      file.verified = false;
      file.importedAt = Instant.now();
      probeService.tryProbe(file);
      file.persist();
      linked++;
    }
    return linked;
  }

  private long sizeOrZero(String path) {
    try {
      return Files.size(Path.of(path));
    } catch (Exception e) {
      return 0L;
    }
  }
}

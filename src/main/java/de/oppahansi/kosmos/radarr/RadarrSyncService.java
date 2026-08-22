package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.library.LibraryFileLinkService;
import de.oppahansi.kosmos.library.LibraryRootFolder;
import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.media.Movie;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.radarr.dto.RadarrLibrarySyncResult;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Imports an already-managed Radarr library into Kosmos — the movie-only counterpart to {@code
 * JellyfinSyncService}. Only movies with a file already on disk today ({@code hasFile}) are
 * imported; a monitored-but-not-yet-downloaded Radarr movie (its "wanted" list) is out of scope —
 * see the roadmap's own note on this. Radarr is TMDB-native (it matches every movie to a TMDB id
 * itself, before Kosmos ever sees it), so unlike Jellyfin sync's {@code enrichFromTmdb} this never
 * needs a year-corroboration/search-recovery dance — Radarr's own reported {@code tmdbId} is
 * trusted directly.
 *
 * <p>Every movie gets its own transaction (same reasoning as {@code JellyfinSyncService}): this
 * runs over a potentially large real library, and one bad item must never roll back everything else
 * already synced in the same run.
 */
@ApplicationScoped
public class RadarrSyncService {

  private static final String TMDB_PLUGIN_SLUG = "tmdb";
  private static final String MATCH_METHOD = "RADARR_SYNC";

  @Inject LibraryFileLinkService libraryFileLinkService;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject RadarrServerService serverService;

  public RadarrLibrarySyncResult syncLibrary(UUID serverId, ProgressReporter progress) {
    RadarrServer server = requireServer(serverId);
    // Same auto-register the Jellyfin flow does inline — otherwise newly-synced movies have
    // nowhere registered to be organized under.
    serverService.autoRegisterRootFolders(serverId);
    RadarrClient client = new RadarrClient(server.baseUrl, server.apiKey);
    List<RadarrMovie> movies;
    try {
      movies = client.listMovies();
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Radarr server: " + e.getMessage());
    }

    int total = movies.size();
    int withFile = 0;
    int processed = 0;
    int linked = 0;
    int created = 0;
    int alreadySynced = 0;
    int skippedNoFile = 0;

    for (RadarrMovie movie : movies) {
      processed++;
      progress.update(processed, total, movie.title());
      if (!movie.hasFile() || movie.path() == null || movie.tmdbId() == null) {
        skippedNoFile++;
        continue;
      }
      withFile++;
      try {
        String outcome = QuarkusTransaction.requiringNew().call(() -> syncOneMovie(movie));
        switch (outcome) {
          case "linked" -> linked++;
          case "created" -> created++;
          default -> alreadySynced++;
        }
      } catch (RuntimeException e) {
        alreadySynced++; // most likely a race on the same path; treat as already-handled
      }
    }

    progress.update(total, total, "Done");
    return new RadarrLibrarySyncResult(
        total, withFile, linked, created, alreadySynced, skippedNoFile);
  }

  private RadarrServer requireServer(UUID serverId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                RadarrServer.<RadarrServer>findByIdOptional(serverId)
                    .orElseThrow(
                        () -> new BadRequestException("Unknown Radarr server id: " + serverId)));
  }

  /**
   * @return "linked", "created", or "already-synced"
   */
  private String syncOneMovie(RadarrMovie movie) {
    Optional<LibraryFile> existingFile =
        LibraryFile.find("path", movie.path()).firstResultOptional();
    if (existingFile.isPresent()) {
      backfillRootFolder(existingFile.get().mediaItem, movie);
      return "already-synced";
    }

    Optional<MediaItem> existingLink =
        externalIdLinkService.findLinkedMediaItem(TMDB_PLUGIN_SLUG, movie.tmdbId());

    MediaItem mediaItem;
    String outcome;
    if (existingLink.isPresent()) {
      mediaItem = existingLink.get();
      outcome = "linked";
      backfillRootFolder(mediaItem, movie);
    } else {
      mediaItem = createMovie(movie);
      outcome = "created";
    }
    libraryFileLinkService.link(mediaItem, movie.path(), MATCH_METHOD);
    return outcome;
  }

  private MediaItem createMovie(RadarrMovie radarrMovie) {
    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "movie";
    mediaItem.title = radarrMovie.title();
    mediaItem.year = radarrMovie.year();
    mediaItem.addedAt = Instant.now();
    resolveRootFolder(radarrMovie.path()).ifPresent(folder -> mediaItem.rootFolder = folder);
    mediaItem.persist();

    Movie movie = new Movie();
    movie.mediaItem = mediaItem;
    movie.persist();

    // Best-effort poster/overview/release-date backfill — Radarr's own list doesn't carry a
    // ready-to-store poster path, only TMDB's own /movie/{id} does.
    Optional<MetadataSearchResult> details =
        tmdbMetadataProvider.fetchMovieById(radarrMovie.tmdbId());
    details.ifPresent(
        r -> {
          movie.posterPath = r.posterPath();
          movie.backdropPath = r.backdropPath();
          movie.overview = r.overview();
          movie.releaseDate = r.releaseDateAsLocalDate();
        });

    externalIdLinkService.link(mediaItem, TMDB_PLUGIN_SLUG, radarrMovie.tmdbId());
    return mediaItem;
  }

  private void backfillRootFolder(MediaItem mediaItem, RadarrMovie radarrMovie) {
    if (mediaItem.rootFolder == null) {
      resolveRootFolder(radarrMovie.path()).ifPresent(folder -> mediaItem.rootFolder = folder);
    }
  }

  /**
   * Prefers the registered root folder Radarr's own reported path actually falls under — same
   * reasoning as {@code JellyfinSyncService#resolveRootFolder} — falling back to the movie default
   * only if none of the registered folders actually contain this path.
   */
  private Optional<LibraryRootFolder> resolveRootFolder(String path) {
    Optional<LibraryRootFolder> containing = rootFolderService.findContaining(path);
    return containing.isPresent() ? containing : rootFolderService.getDefault("movie");
  }
}

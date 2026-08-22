package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.library.dto.RefreshScanResult;
import de.oppahansi.kosmos.media.Anime;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.media.Movie;
import de.oppahansi.kosmos.media.Show;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.anilist.AniListMetadataProvider;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Refresh & Scan — an on-demand version of what a sync/import already does on its own schedule.
 * "Refresh" re-fetches metadata (TMDB for movies/shows, AniList for anime — poster/backdrop/
 * overview), bypassing the normal multi-day {@code @CacheResult} window via {@link
 * CacheManager#getCache}/{@link Cache#invalidate} first — a stale cache would otherwise make
 * "Refresh" silently do nothing for up to a week, which isn't what a user clicking it is asking
 * for. "Scan" re-lists the title's own folder (movies only — see below) for a video file {@link
 * LibraryFile} doesn't know about yet, via the exact same folder the real import path would compute
 * (see {@link ImportService#movieFolder}), and links any it finds through {@link
 * LibraryFileLinkService} directly — unlike {@code ImportMatchService}'s bulk-import scan, there's
 * no ambiguity to review here: the folder itself already identifies the movie.
 *
 * <p>Scan is movies-only for now — a show's expected file location is per-episode (season
 * subfolder, per-episode naming), materially more work than one folder listing; deferred rather
 * than half-built. Metadata refresh covers both movies and shows.
 */
@ApplicationScoped
public class RefreshScanService {

  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject AniListMetadataProvider aniListMetadataProvider;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject LibraryFileLinkService libraryFileLinkService;
  @Inject ImportService importService;
  @Inject CacheManager cacheManager;

  @Transactional
  public RefreshScanResult refreshMovie(UUID movieId) {
    Movie movie =
        Movie.<Movie>findByIdOptional(movieId)
            .orElseThrow(() -> new NotFoundException("Unknown movie id: " + movieId));
    boolean metadataRefreshed =
        externalIdLinkService
            .findActiveExternalId(movieId, "tmdb")
            .map(
                tmdbId -> {
                  invalidate("tmdb-movie-by-id", tmdbId);
                  return tmdbMetadataProvider
                      .fetchMovieById(tmdbId)
                      .map(
                          r -> {
                            movie.posterPath = r.posterPath();
                            movie.backdropPath = r.backdropPath();
                            movie.overview = r.overview();
                            movie.releaseDate = r.releaseDateAsLocalDate();
                            return true;
                          })
                      .orElse(false);
                })
            .orElse(false);

    int filesLinked = scanMovieFolder(movie.mediaItem);
    return new RefreshScanResult(metadataRefreshed, filesLinked);
  }

  @Transactional
  public RefreshScanResult refreshShow(UUID showId) {
    Show show =
        Show.<Show>findByIdOptional(showId)
            .orElseThrow(() -> new NotFoundException("Unknown show id: " + showId));
    boolean metadataRefreshed =
        externalIdLinkService
            .findActiveExternalId(showId, "tmdb")
            .map(
                tmdbId -> {
                  invalidate("tmdb-show-by-id", tmdbId);
                  return tmdbMetadataProvider
                      .fetchShowById(tmdbId)
                      .map(
                          (MetadataSearchResult r) -> {
                            show.posterPath = r.posterPath();
                            show.backdropPath = r.backdropPath();
                            show.overview = r.overview();
                            return true;
                          })
                      .orElse(false);
                })
            .orElse(false);
    return new RefreshScanResult(metadataRefreshed, 0);
  }

  /** Anime's own metadata source is AniList, not TMDB — see {@code AnimeService}'s own doc. */
  @Transactional
  public RefreshScanResult refreshAnime(UUID animeId) {
    Anime anime =
        Anime.<Anime>findByIdOptional(animeId)
            .orElseThrow(() -> new NotFoundException("Unknown anime id: " + animeId));
    boolean metadataRefreshed =
        externalIdLinkService
            .findActiveExternalId(animeId, "anilist")
            .map(
                anilistId -> {
                  invalidate("anilist-media", anilistId);
                  return aniListMetadataProvider
                      .fetchById(anilistId)
                      .map(
                          r -> {
                            anime.posterPath = r.posterPath();
                            anime.overview = r.overview();
                            return true;
                          })
                      .orElse(false);
                })
            .orElse(false);
    return new RefreshScanResult(metadataRefreshed, 0);
  }

  private int scanMovieFolder(MediaItem mediaItem) {
    Path folder = importService.movieFolder(mediaItem);
    if (!Files.exists(folder)) {
      return 0;
    }
    List<Path> videos = VideoFileScanner.findAll(folder);
    int linked = 0;
    for (Path video : videos) {
      String path = video.toString();
      if (LibraryFile.count("path", path) > 0) {
        continue;
      }
      libraryFileLinkService.link(mediaItem, path, "RESCAN");
      linked++;
    }
    return linked;
  }

  private void invalidate(String cacheName, String key) {
    Optional<Cache> cache = cacheManager.getCache(cacheName);
    cache.ifPresent(c -> c.invalidate(key).await().indefinitely());
  }
}

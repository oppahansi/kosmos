package de.oppahansi.kosmos.radarr;

/**
 * One movie as reported by {@code GET /api/v3/movie} — {@code path} is the movie's own file when
 * {@code hasFile} is true (from the response's {@code movieFile.path}), null otherwise. Radarr is
 * TMDB-native: {@code tmdbId} is always the id Radarr itself matched the movie to, no recovery
 * logic needed the way {@code JellyfinSyncService} needs for Jellyfin's own scraped ids.
 */
public record RadarrMovie(
    String title, Integer year, String tmdbId, boolean hasFile, String path) {}

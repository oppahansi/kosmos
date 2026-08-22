package de.oppahansi.kosmos.sonarr;

/**
 * One series as reported by {@code GET /api/v3/series} — {@code tmdbId} is Sonarr's own native
 * field when it has one (most titles today), null otherwise; {@code tvdbId} (Sonarr's primary,
 * always-present identity) is the fallback {@code SonarrSyncService} resolves to a TMDB id via
 * {@code TmdbMetadataProvider#findTvIdByTvdbId} when {@code tmdbId} is missing.
 */
public record SonarrSeries(
    int id, String title, Integer year, String tmdbId, String tvdbId, String path) {}

package de.oppahansi.kosmos.sonarr.dto;

/**
 * @param total every series Sonarr reported, monitored or not
 * @param linked matched to an existing Kosmos {@code MediaItem} by TMDB id
 * @param created no existing match — a new {@code MediaItem}/{@code Show} was created
 * @param alreadySynced a series with no newly-linked episode files this run
 * @param skippedNoId neither a native {@code tmdbId} nor a resolvable {@code tvdbId} — see {@code
 *     SonarrSyncService}
 * @param episodeFilesLinked total episode files matched across every series this run
 */
public record SonarrLibrarySyncResult(
    int total,
    int linked,
    int created,
    int alreadySynced,
    int skippedNoId,
    int episodeFilesLinked) {}

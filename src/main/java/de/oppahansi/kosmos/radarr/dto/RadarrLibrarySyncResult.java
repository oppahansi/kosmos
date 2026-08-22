package de.oppahansi.kosmos.radarr.dto;

/**
 * @param total every movie Radarr reported, monitored or not
 * @param withFile how many of those have a file on disk today (the only ones actually imported —
 *     see {@code RadarrSyncService})
 * @param linked matched to an existing Kosmos {@code MediaItem} by TMDB id
 * @param created no existing match — a new {@code MediaItem}/{@code Movie} was created
 * @param alreadySynced a {@code LibraryFile} already existed at that exact path
 * @param skippedNoFile monitored but not downloaded — out of scope, see {@code RadarrSyncService}
 */
public record RadarrLibrarySyncResult(
    int total, int withFile, int linked, int created, int alreadySynced, int skippedNoFile) {}

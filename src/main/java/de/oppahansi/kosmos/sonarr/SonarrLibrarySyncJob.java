package de.oppahansi.kosmos.sonarr;

import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import de.oppahansi.kosmos.sonarr.dto.SonarrLibrarySyncResult;
import java.util.UUID;

/**
 * Syncs one configured {@link SonarrServer}'s library. Not a CDI bean — the set of servers is
 * runtime-configurable, so instances are built fresh by {@link SonarrSyncJobs} rather than
 * discovered as a fixed set. Never auto-scheduled (see {@link #autoScheduled}) — the user starts it
 * explicitly, from Settings → Sonarr.
 */
public class SonarrLibrarySyncJob implements JobHandler {

  private final UUID serverId;
  private final String serverName;
  private final SonarrSyncService syncService;

  SonarrLibrarySyncJob(UUID serverId, String serverName, SonarrSyncService syncService) {
    this.serverId = serverId;
    this.serverName = serverName;
    this.syncService = syncService;
  }

  @Override
  public String jobName() {
    return "sonarr-library-sync-" + serverId;
  }

  @Override
  public String displayName() {
    return "Sonarr Library Sync — " + serverName;
  }

  @Override
  public int defaultIntervalSeconds() {
    return 1800; // 30 minutes — only takes effect if the user opts into scheduling it
  }

  @Override
  public boolean autoScheduled() {
    return false;
  }

  @Override
  public String run(ProgressReporter progress) {
    return summarize(syncService.syncLibrary(serverId, progress));
  }

  static String summarize(SonarrLibrarySyncResult result) {
    String base =
        "Synced: %d series added, %d already-owned linked, %d episode files linked."
            .formatted(result.created(), result.linked(), result.episodeFilesLinked());
    return result.skippedNoId() > 0
        ? base + " %d skipped (no resolvable TMDB id).".formatted(result.skippedNoId())
        : base;
  }
}

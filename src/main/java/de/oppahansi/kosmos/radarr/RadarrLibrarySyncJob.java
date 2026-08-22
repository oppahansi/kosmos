package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.radarr.dto.RadarrLibrarySyncResult;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import java.util.UUID;

/**
 * Syncs one configured {@link RadarrServer}'s library. Not a CDI bean — the set of servers is
 * runtime-configurable, so instances are built fresh by {@link RadarrSyncJobs} rather than
 * discovered as a fixed set. Never auto-scheduled (see {@link #autoScheduled}) — the user starts it
 * explicitly, from Settings → Radarr.
 */
public class RadarrLibrarySyncJob implements JobHandler {

  private final UUID serverId;
  private final String serverName;
  private final RadarrSyncService syncService;

  RadarrLibrarySyncJob(UUID serverId, String serverName, RadarrSyncService syncService) {
    this.serverId = serverId;
    this.serverName = serverName;
    this.syncService = syncService;
  }

  @Override
  public String jobName() {
    return "radarr-library-sync-" + serverId;
  }

  @Override
  public String displayName() {
    return "Radarr Library Sync — " + serverName;
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

  static String summarize(RadarrLibrarySyncResult result) {
    String base =
        "Synced: %d movies added, %d already-owned linked, %d already imported."
            .formatted(result.created(), result.linked(), result.alreadySynced());
    return result.skippedNoFile() > 0
        ? base + " %d monitored but not downloaded, skipped.".formatted(result.skippedNoFile())
        : base;
  }
}

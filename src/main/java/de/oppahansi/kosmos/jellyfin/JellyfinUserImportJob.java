package de.oppahansi.kosmos.jellyfin;

import de.oppahansi.kosmos.jellyfin.dto.JellyfinUserSyncResult;
import de.oppahansi.kosmos.scheduler.JobCategory;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import java.util.UUID;

/**
 * Imports one configured {@link JellyfinServer}'s selected user accounts. Not a CDI bean, for the
 * same reason as {@link JellyfinLibrarySyncJob}. Never auto-scheduled — see {@link #autoScheduled}.
 */
public class JellyfinUserImportJob implements JobHandler {

  private final UUID serverId;
  private final String serverName;
  private final JellyfinSyncService syncService;

  JellyfinUserImportJob(UUID serverId, String serverName, JellyfinSyncService syncService) {
    this.serverId = serverId;
    this.serverName = serverName;
    this.syncService = syncService;
  }

  @Override
  public String jobName() {
    return "jellyfin-user-import-" + serverId;
  }

  @Override
  public String displayName() {
    return "Jellyfin User Import — " + serverName;
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
  public JobCategory category() {
    return JobCategory.SERVER;
  }

  @Override
  public String run(ProgressReporter progress) {
    return summarize(syncService.syncUsers(serverId, progress));
  }

  static String summarize(JellyfinUserSyncResult result) {
    return "Imported: %d users added, %d updated, %d skipped (not selected)."
        .formatted(result.created(), result.updated(), result.skippedNotSelected());
  }
}

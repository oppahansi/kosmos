package de.oppahansi.kosmos.jellyfin;

import de.oppahansi.kosmos.jellyfin.dto.JellyfinLibrarySyncResult;
import de.oppahansi.kosmos.scheduler.JobCategory;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import java.util.UUID;

/**
 * Syncs one configured {@link JellyfinServer}'s selected libraries. Not a CDI bean — the set of
 * servers is runtime-configurable, so instances are built fresh by {@link JellyfinSyncJobs} rather
 * than discovered as a fixed set like the download-search {@code JobHandler}s. Never auto-scheduled
 * (see {@link #autoScheduled}) — the user starts it explicitly, from Settings → Servers.
 */
public class JellyfinLibrarySyncJob implements JobHandler {

  private final UUID serverId;
  private final String serverName;
  private final JellyfinSyncService syncService;

  JellyfinLibrarySyncJob(UUID serverId, String serverName, JellyfinSyncService syncService) {
    this.serverId = serverId;
    this.serverName = serverName;
    this.syncService = syncService;
  }

  @Override
  public String jobName() {
    return "jellyfin-library-sync-" + serverId;
  }

  @Override
  public String displayName() {
    return "Jellyfin Library Sync — " + serverName;
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
    return summarize(syncService.syncLibraries(serverId, progress));
  }

  static String summarize(JellyfinLibrarySyncResult result) {
    String base =
        ("Synced: %d movies added, %d already-owned linked, %d series added, %d anime added, %d"
                + " episode files linked.")
            .formatted(
                result.created(),
                result.linked(),
                result.showsCreated(),
                result.animeCreated(),
                result.episodeFilesLinked());
    return result.needsReview() > 0
        ? base + " %d need review.".formatted(result.needsReview())
        : base;
  }
}

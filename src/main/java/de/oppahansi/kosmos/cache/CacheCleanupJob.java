package de.oppahansi.kosmos.cache;

import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Deletes expired {@link CacheEntry} rows daily. Durable storage means unbounded growth is a real
 * risk here in a way Caffeine's old JVM-heap {@code maximum-size} eviction never had to worry about
 * — nothing else prunes this table. Safe to run unattended (a purely internal, idempotent delete),
 * so unlike {@code BackupJob} this starts auto-scheduled.
 */
@ApplicationScoped
public class CacheCleanupJob implements JobHandler {

  @Inject PersistentCacheService persistentCacheService;

  @Override
  public String jobName() {
    return "cache-cleanup";
  }

  @Override
  public String displayName() {
    return "Cache Cleanup";
  }

  @Override
  public int defaultIntervalSeconds() {
    return 86_400;
  }

  @Override
  public String run(ProgressReporter progress) {
    long deleted = persistentCacheService.deleteExpired();
    return "Deleted " + deleted + " expired cache entr" + (deleted == 1 ? "y" : "ies") + ".";
  }
}

package de.oppahansi.kosmos.scheduler;

/**
 * A recurring background task. Implementations are CDI beans discovered automatically by {@link
 * JobScheduler}.
 */
public interface JobHandler {

  /** Must match the {@link ScheduledJob#name} row this handler runs for. */
  String jobName();

  /** Human-readable label for the jobs settings page and failure notifications. */
  String displayName();

  /** Used only to seed the {@link ScheduledJob} row the first time this handler is seen. */
  int defaultIntervalSeconds();

  /**
   * Whether this job's row starts out enabled — and so eligible for {@link JobScheduler#tick}'s
   * automatic schedule — the first time it's seeded. Only consulted on that first seed; a user's
   * later toggle in the settings UI always wins. Defaults to {@code true}, matching every job
   * before this existed; a job that should only ever run on explicit request (e.g. "Run Now" /
   * "Sync") overrides it to {@code false}.
   */
  default boolean autoScheduled() {
    return true;
  }

  /**
   * Which section of the Jobs settings page this job groups under. Defaults to {@link
   * JobCategory#KOSMOS}; the handful of jobs tied to a specific configured Jellyfin/Radarr/Sonarr
   * server override this to {@link JobCategory#SERVER}.
   */
  default JobCategory category() {
    return JobCategory.KOSMOS;
  }

  /**
   * Runs the job, reporting progress through {@code progress} if there's anything granular worth
   * showing (most handlers never call it). The returned value (or {@code null}) becomes {@link
   * ScheduledJob#lastMessage} and the {@link JobRun#message} of a successful run. Throw to record a
   * failed run instead — the exception message reaches the same two fields.
   */
  String run(ProgressReporter progress);
}

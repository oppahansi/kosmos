package de.oppahansi.kosmos.scheduler.dto;

import de.oppahansi.kosmos.scheduler.JobRun;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the Jobs settings page's global "Recent Activity" section — unlike {@link
 * JobRunResponse} (a single job's own expanded history, where the job is already implied by
 * context), a cross-job list needs {@code jobDisplayName} on every row to say which job it was.
 */
public record RecentJobRunResponse(
    UUID id,
    String jobName,
    String jobDisplayName,
    Instant startedAt,
    Instant finishedAt,
    String status,
    String message) {

  public static RecentJobRunResponse from(JobRun run) {
    return new RecentJobRunResponse(
        run.id,
        run.jobName,
        run.jobDisplayName,
        run.startedAt,
        run.finishedAt,
        run.status,
        run.message);
  }
}

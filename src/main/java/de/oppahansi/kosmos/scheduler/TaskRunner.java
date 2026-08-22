package de.oppahansi.kosmos.scheduler;

import de.oppahansi.kosmos.notifications.NotificationService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * Runs and records a one-off action — no {@link ScheduledJob} identity, no due/interval/claim
 * logic, just "run this now and keep a {@link JobRun} row for it." {@link JobHandler}/{@link
 * JobRunner} model a *named, recurring* thing re-run against the same identity every time, which is
 * exactly right for "sync this Radarr server" and wrong for "import the files this user just
 * reviewed" — that happens once and never again under that identity, so forcing a fake {@link
 * ScheduledJob} row into existence for it would be modeling the wrong thing.
 *
 * <p>Deliberately does not touch {@link JobProgressBroadcaster}: the existing per-job SSE pattern
 * only works because a subscriber mounts *ambiently, before any click* — a job's name is stable and
 * known ahead of time (a settings row that already exists). A one-off task's identity is only
 * created at the moment of the call, so there's nothing stable to predict and subscribe to in time;
 * broadcasting under a synthetic per-call name would either race a client that can't possibly have
 * subscribed yet, or leak an entry in {@code JobProgressBroadcaster}'s map (which has no eviction).
 * The scope this backs (a reviewed batch, synchronous, well under a request timeout — matching
 * {@link JobResource#runNow}'s own "every current job handler finishes in well under a request
 * timeout") doesn't need it: the caller already has the full result in the HTTP response.
 */
@ApplicationScoped
public class TaskRunner {

  @Inject NotificationService notificationService;

  public JobRun run(String taskName, String displayName, Supplier<String> body) {
    Instant startedAt = Instant.now();
    JobRunOutcome.Result result = JobRunOutcome.of(body);
    JobRun run = persist(taskName, displayName, startedAt, result);

    if ("FAILED".equals(result.status())) {
      notificationService.notifyAll(
          "Task failed",
          displayName + " failed" + (result.message() != null ? ": " + result.message() : "."));
    }
    return run;
  }

  @Transactional
  JobRun persist(
      String taskName, String displayName, Instant startedAt, JobRunOutcome.Result result) {
    JobRun run = new JobRun();
    run.jobName = taskName;
    run.jobDisplayName = displayName;
    run.startedAt = startedAt;
    run.finishedAt = Instant.now();
    run.status = result.status();
    run.message = result.message();
    run.persist();
    return run;
  }
}

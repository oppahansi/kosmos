package de.oppahansi.kosmos.scheduler;

import de.oppahansi.kosmos.notifications.NotificationService;
import de.oppahansi.kosmos.scheduler.dto.JobProgressEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Executes a single {@link JobHandler}. Claiming a job ({@link #claim}: checking it's due/not
 * already running, and marking it running) and recording its outcome ({@link #finish}) are each
 * their own committed transaction, with the handler's own {@link JobHandler#run} in between outside
 * any transaction of this class's own — unlike the previous single-transaction design, this means a
 * concurrent {@link JobScheduler#tick()} sees "already running" the instant a job is claimed, not
 * only after the whole run (which can include the handler's own slow external HTTP calls) finally
 * commits.
 */
@ApplicationScoped
public class JobRunner {

  @Inject NotificationService notificationService;
  @Inject JobProgressBroadcaster progressBroadcaster;

  /** Runs {@code handler} if it's due and not already running — a no-op otherwise. */
  public void runIfDue(JobHandler handler) {
    run(handler, false);
  }

  /**
   * Runs {@code handler} right now regardless of its schedule, unless it's already mid-run. Returns
   * the resulting {@link JobRun}, or empty if it was already running.
   */
  public Optional<JobRun> runNow(JobHandler handler) {
    return Optional.ofNullable(run(handler, true));
  }

  private JobRun run(JobHandler handler, boolean force) {
    ScheduledJob job = QuarkusTransaction.requiringNew().call(() -> claim(handler, force));
    if (job == null) {
      return null;
    }

    UUID jobId = job.id;
    String jobName = handler.jobName();
    // Fires even for handlers that never report granular progress — this is what lets a subscriber
    // learn "it's running" the instant it's claimed, not only once/if the first progress event
    // does.
    progressBroadcaster.publish(jobName, JobProgressEvent.started());

    Instant startedAt = Instant.now();
    JobRunOutcome.Result result =
        JobRunOutcome.of(
            () ->
                handler.run(
                    (current, total, msg) ->
                        progressBroadcaster.publish(
                            jobName, JobProgressEvent.progress(current, total, msg))));

    JobRun jobRun = QuarkusTransaction.requiringNew().call(() -> finish(jobId, startedAt, result));

    progressBroadcaster.publish(
        jobName, JobProgressEvent.finished(result.status(), result.message()));

    if ("FAILED".equals(result.status())) {
      notificationService.notifyAll(
          "Job failed",
          handler.displayName()
              + " failed"
              + (result.message() != null ? ": " + result.message() : "."));
    }
    return jobRun;
  }

  private ScheduledJob claim(JobHandler handler, boolean force) {
    ScheduledJob job = findOrSeed(handler);
    boolean runnable = force ? job.runningSince == null : job.isDue(Instant.now());
    if (!runnable) {
      return null;
    }
    job.runningSince = Instant.now();
    return job;
  }

  private JobRun finish(UUID jobId, Instant startedAt, JobRunOutcome.Result result) {
    ScheduledJob job = ScheduledJob.<ScheduledJob>findById(jobId);
    job.runningSince = null;
    job.lastRunAt = startedAt;
    job.lastStatus = result.status();
    job.lastMessage = result.message();

    JobRun jobRun = new JobRun();
    jobRun.scheduledJob = job;
    jobRun.jobName = job.name;
    jobRun.jobDisplayName = job.displayName;
    jobRun.startedAt = startedAt;
    jobRun.finishedAt = Instant.now();
    jobRun.status = result.status();
    jobRun.message = result.message();
    jobRun.persist();
    return jobRun;
  }

  private ScheduledJob findOrSeed(JobHandler handler) {
    Optional<ScheduledJob> existing =
        ScheduledJob.<ScheduledJob>find("name", handler.jobName()).firstResultOptional();
    if (existing.isPresent()) {
      ScheduledJob job = existing.get();
      // Code-owned, unlike intervalSeconds/enabled which the user can edit — always kept in sync
      // so a handler rename (or a category change) doesn't leave a stale value in the DB.
      job.displayName = handler.displayName();
      job.category = handler.category().name();
      return job;
    }
    ScheduledJob job = new ScheduledJob();
    job.name = handler.jobName();
    job.displayName = handler.displayName();
    job.category = handler.category().name();
    job.intervalSeconds = handler.defaultIntervalSeconds();
    job.enabled = handler.autoScheduled();
    job.createdAt = Instant.now();
    job.persist();
    return job;
  }
}

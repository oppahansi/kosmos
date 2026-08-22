package de.oppahansi.kosmos.scheduler;

import de.oppahansi.kosmos.common.KosmosEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/** Config + last-run state for a recurring background task. See {@link JobHandler}. */
@Entity
@Table(name = "scheduled_job")
public class ScheduledJob extends KosmosEntity {

  @Column(nullable = false, unique = true, length = 100)
  public String name;

  @Column(name = "display_name", nullable = false, length = 200)
  public String displayName;

  @Column(name = "interval_seconds", nullable = false)
  public int intervalSeconds;

  @Column(nullable = false)
  public boolean enabled;

  /**
   * Set for the duration of a run — see {@link JobRunner} for why this is its own committed
   * transaction rather than a flag checked within the run's own transaction. Null when idle.
   */
  @Column(name = "running_since")
  public Instant runningSince;

  @Column(name = "last_run_at")
  public Instant lastRunAt;

  @Column(name = "last_status", length = 20)
  public String lastStatus;

  @Column(name = "last_message", length = 1000)
  public String lastMessage;

  /**
   * {@link JobCategory#name()} — code-owned, refreshed from {@link JobHandler#category()} on every
   * claim, same treatment {@link #displayName} already gets.
   */
  @Column(nullable = false, length = 20)
  public String category;

  /** First-seen order — see {@link JobService#listAll()} for why the settings page sorts by it. */
  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  public boolean isDue(Instant now) {
    return enabled
        && runningSince == null
        && (lastRunAt == null || lastRunAt.plusSeconds(intervalSeconds).isBefore(now));
  }
}

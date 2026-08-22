package de.oppahansi.kosmos.scheduler;

import de.oppahansi.kosmos.common.KosmosEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One execution of a {@link ScheduledJob} (recurring) or a one-off {@link TaskRunner} invocation
 * (e.g. a manual bulk-import commit) — the history the Jobs settings page reads. {@link
 * #scheduledJob} is null for the latter; {@link #jobName}/{@link #jobDisplayName} are denormalized
 * at creation time (same convention {@code HistoryEvent.title} already uses) so a row still reads
 * correctly with no {@link ScheduledJob} behind it at all, or after one is later renamed.
 */
@Entity
@Table(name = "job_run")
public class JobRun extends KosmosEntity {

  @ManyToOne
  @JoinColumn(name = "scheduled_job_id")
  public ScheduledJob scheduledJob;

  @Column(name = "job_name", nullable = false, length = 100)
  public String jobName;

  @Column(name = "job_display_name", nullable = false, length = 200)
  public String jobDisplayName;

  @Column(name = "started_at", nullable = false)
  public Instant startedAt;

  @Column(name = "finished_at")
  public Instant finishedAt;

  @Column(nullable = false, length = 20)
  public String status;

  @Column(length = 1000)
  public String message;
}

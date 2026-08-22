package de.oppahansi.kosmos.scheduler.dto;

import de.oppahansi.kosmos.scheduler.ScheduledJob;
import java.time.Instant;
import java.util.UUID;

public record ScheduledJobResponse(
    UUID id,
    String name,
    String displayName,
    String category,
    int intervalSeconds,
    boolean enabled,
    boolean running,
    Instant lastRunAt,
    String lastStatus,
    String lastMessage) {

  public static ScheduledJobResponse from(ScheduledJob job) {
    return new ScheduledJobResponse(
        job.id,
        job.name,
        job.displayName,
        job.category,
        job.intervalSeconds,
        job.enabled,
        job.runningSince != null,
        job.lastRunAt,
        job.lastStatus,
        job.lastMessage);
  }
}

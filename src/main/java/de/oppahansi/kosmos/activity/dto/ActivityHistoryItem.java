package de.oppahansi.kosmos.activity.dto;

import de.oppahansi.kosmos.activity.HistoryEvent;
import java.time.Instant;
import java.util.UUID;

public record ActivityHistoryItem(
    UUID id,
    UUID mediaItemId,
    String title,
    String kind,
    String message,
    Long sizeBytes,
    Instant occurredAt) {

  public static ActivityHistoryItem from(HistoryEvent event) {
    return new ActivityHistoryItem(
        event.id,
        event.mediaItem != null ? event.mediaItem.id : null,
        event.title,
        event.kind,
        event.message,
        event.sizeBytes,
        event.occurredAt);
  }
}

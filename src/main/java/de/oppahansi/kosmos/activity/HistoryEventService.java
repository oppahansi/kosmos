package de.oppahansi.kosmos.activity;

import de.oppahansi.kosmos.media.MediaItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class HistoryEventService {

  @Transactional
  public void record(MediaItem mediaItem, String kind, String message, Long sizeBytes) {
    HistoryEvent event = new HistoryEvent();
    event.mediaItem = mediaItem;
    event.title = mediaItem.title;
    event.kind = kind;
    event.message = message;
    event.sizeBytes = sizeBytes;
    event.occurredAt = Instant.now();
    event.persist();
  }

  public List<HistoryEvent> recent(int limit) {
    return HistoryEvent.find("order by occurredAt desc").page(0, limit).list();
  }

  public List<HistoryEvent> forMediaItem(UUID mediaItemId) {
    return HistoryEvent.list("mediaItem.id = ?1 order by occurredAt desc", mediaItemId);
  }

  /**
   * Real count, not capped by whatever page size {@link #recent} was asked for — a stat card
   * showing "today's" total must reflect the actual day, not just however many rows the caller
   * happened to fetch.
   */
  public long countSince(String kind, Instant since) {
    return HistoryEvent.count("kind = ?1 and occurredAt >= ?2", kind, since);
  }
}

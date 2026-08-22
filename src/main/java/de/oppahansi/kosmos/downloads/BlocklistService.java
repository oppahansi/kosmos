package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.indexers.Release;
import de.oppahansi.kosmos.notifications.NotificationEvent;
import de.oppahansi.kosmos.notifications.NotificationEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Reads and writes {@link Blocklist} — see that entity's own doc for what it represents. */
@ApplicationScoped
public class BlocklistService {

  @Inject Event<NotificationEvent> notificationEvent;

  /**
   * Idempotent: a release already blocklisted for this media item (the unique constraint this
   * relies on) just means a second failure signal arrived for the same one — nothing new to record,
   * and no repeat notification either.
   */
  @Transactional
  public void blockRelease(Release release, String reason) {
    if (isBlocked(release.mediaItem.id, release.downloadUrl)) {
      return;
    }
    Blocklist entry = new Blocklist();
    entry.mediaItem = release.mediaItem;
    entry.downloadUrl = release.downloadUrl;
    entry.titleRaw = release.titleRaw;
    entry.reason = reason;
    entry.blockedAt = Instant.now();
    entry.persist();
    notificationEvent.fire(
        new NotificationEvent(
            NotificationEventType.BLOCKLIST,
            release.mediaItem.id,
            release.titleRaw,
            "Blocklisted \"" + release.titleRaw + "\": " + reason,
            null));
  }

  public boolean isBlocked(UUID mediaItemId, String downloadUrl) {
    return Blocklist.count("mediaItem.id = ?1 and downloadUrl = ?2", mediaItemId, downloadUrl) > 0;
  }

  public List<Blocklist> listAll() {
    return Blocklist.list("order by blockedAt desc");
  }

  @Transactional
  public boolean remove(UUID id) {
    return Blocklist.deleteById(id);
  }
}

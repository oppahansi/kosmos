package de.oppahansi.kosmos.activity;

import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.notifications.NotificationEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/**
 * Turns every {@link NotificationEvent} (grab/import/blocklist — see that record's own doc) into a
 * real {@link HistoryEvent} row, reusing the same firing this codebase already does for
 * notifications rather than adding a second, parallel "record history" call at each of those sites.
 * Observes the same {@code AFTER_SUCCESS} phase {@code NotificationService} does, for the same
 * reason: only an event whose transaction actually committed should be recorded.
 *
 * <p>A bulk sync import (Jellyfin/Radarr/Sonarr — see {@code
 * de.oppahansi.kosmos.library.LibraryFileLinkService}) deliberately does NOT fire a {@link
 * NotificationEvent} — hundreds of individual notifications for one sync run would spam every
 * configured notifier — so it calls {@link HistoryEventService#record} directly instead. This class
 * is only the notification-triggered half of what ends up in history.
 */
@ApplicationScoped
public class HistoryEventRecorder {

  @Inject HistoryEventService historyEventService;

  void onEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) NotificationEvent event) {
    if (event.mediaItemId() == null) {
      return;
    }
    MediaItem mediaItem = MediaItem.<MediaItem>findById(event.mediaItemId());
    if (mediaItem == null) {
      return; // title deleted between the original commit and this observer running
    }
    String kind =
        switch (event.type()) {
          case GRAB -> "GRABBED";
          case IMPORT -> "IMPORTED";
          case BLOCKLIST -> "FAILED";
        };
    historyEventService.record(mediaItem, kind, event.message(), event.sizeBytes());
  }
}

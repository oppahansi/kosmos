package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.library.dto.LibraryChangeEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory pub/sub for "a title was created or updated" — SSE, not polling, structurally identical
 * to {@link de.oppahansi.kosmos.scheduler.JobProgressBroadcaster} but one global channel rather
 * than one per job name, since a change isn't scoped to a single job: any of the three sync
 * services, a manual Scan, or a manual import can produce one, and a subscriber (the Library page,
 * the sidebar) wants "anything of this content type changed," not "anything from job X changed."
 *
 * <p>{@code LibraryFileLinkService.link()} and {@code ImportService.importPath()} — the two choke
 * points every title-creating path in the app already funnels through — publish here directly.
 * Neither fires {@code NotificationEvent} for this: that event exists to tell external services
 * (Discord/Telegram) and the Activity history table something happened, and {@code
 * LibraryFileLinkService} deliberately skips it during a bulk sync to avoid spamming a notifier
 * with hundreds of messages. "Tell external services" and "tell the frontend something changed" are
 * different concerns that happened to share one event type before this — they don't anymore.
 *
 * <p>No "replay last event" the way job progress has: a page that just mounted already did a full
 * fetch, so replaying one stale pointer buys it nothing. Deliberately no delete events in this
 * first version either — nothing observed today needs live cross-tab delete, and the record shape
 * doesn't preclude adding a {@code kind} field later if that changes.
 */
@ApplicationScoped
public class LibraryChangeBroadcaster {

  private final BroadcastProcessor<LibraryChangeEvent> processor = BroadcastProcessor.create();

  public Multi<LibraryChangeEvent> subscribe() {
    return processor;
  }

  public void publish(LibraryChangeEvent event) {
    processor.onNext(event);
  }
}

package de.oppahansi.kosmos.notifications;

import java.util.UUID;

/**
 * Fired by whichever service's own transaction just committed the thing this describes — see {@link
 * NotificationService}, which observes it {@code AFTER_SUCCESS} so a notification only ever follows
 * a change that actually stuck, and a slow/unreachable notifier target can't hold that transaction
 * open. One generic record covers every {@link NotificationEventType} rather than a separate event
 * class per type (the old, movie-only {@code MovieImportedEvent} this replaces) — adding a new
 * event kind is now "fire this with a new type," not "write a new class and a new observer method."
 *
 * <p>{@code mediaItemId}/{@code sizeBytes} exist for {@code activity.HistoryEventRecorder}, a
 * second observer of this same event that writes a real {@code HistoryEvent} row — reusing this
 * event rather than adding a parallel history-only firing at every call site. {@code sizeBytes} is
 * null when not applicable (e.g. {@link NotificationEventType#BLOCKLIST}).
 */
public record NotificationEvent(
    NotificationEventType type, UUID mediaItemId, String title, String message, Long sizeBytes) {}

package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.activity.HistoryEventService;
import de.oppahansi.kosmos.library.dto.LibraryChangeEvent;
import de.oppahansi.kosmos.media.MediaItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Records that a {@link MediaItem} owns the file at {@code path} — the one piece of logic {@code
 * JellyfinSyncService}, {@code RadarrSyncService}, and {@code SonarrSyncService} each used to
 * hand-roll identically (path/size/matchMethod/matchConfidence/matchPinned/matchedAt/verified/
 * importedAt/probe/persist, five near-duplicate copies total across movie and episode linking).
 * Deliberately does not fire a {@code NotificationEvent} — a bulk sync run linking hundreds of
 * files would spam every configured notifier — but does record a real {@link
 * de.oppahansi.kosmos.activity.HistoryEvent} directly via {@link HistoryEventService}, so a
 * sync-imported file still shows up in Activity history, just silently. Does publish a {@link
 * LibraryChangeEvent} though — see {@link LibraryChangeBroadcaster}'s own doc for why that's a
 * genuinely separate concern from notifier spam, not the same one this class already opted out of.
 */
@ApplicationScoped
public class LibraryFileLinkService {

  @Inject ProbeService probeService;
  @Inject HistoryEventService historyEventService;
  @Inject LibraryChangeBroadcaster libraryChangeBroadcaster;

  @Transactional
  public LibraryFile link(MediaItem mediaItem, String path, String matchMethod) {
    LibraryFile file = new LibraryFile();
    file.mediaItem = mediaItem;
    file.path = path;
    file.sizeBytes = sizeOrZero(path);
    file.matchMethod = matchMethod;
    file.matchConfidence = 1.0f;
    file.matchPinned = false;
    file.matchedAt = Instant.now();
    file.verified = false;
    file.importedAt = Instant.now();
    probeService.tryProbe(file); // best-effort — only succeeds if Kosmos can see this path too
    file.persist();

    historyEventService.record(
        mediaItem,
        "IMPORTED",
        mediaItem.title + " matched from " + matchMethod.toLowerCase().replace('_', ' ') + ".",
        file.sizeBytes);
    libraryChangeBroadcaster.publish(new LibraryChangeEvent(mediaItem.contentType, mediaItem.id));
    return file;
  }

  private long sizeOrZero(String path) {
    try {
      return Files.size(Path.of(path));
    } catch (Exception e) {
      return 0L;
    }
  }
}

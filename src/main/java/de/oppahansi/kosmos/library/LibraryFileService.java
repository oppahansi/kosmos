package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.activity.HistoryEventService;
import de.oppahansi.kosmos.library.dto.PreviewRenameResult;
import de.oppahansi.kosmos.media.MediaItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Manage Files: deleting, re-matching, or previewing a rename for one already-imported {@link
 * LibraryFile}.
 */
@ApplicationScoped
public class LibraryFileService {

  @Inject EntityManager em;
  @Inject HistoryEventService historyEventService;
  @Inject ImportService importService;

  /**
   * {@code deleteFromDisk=true} also removes the file itself (best-effort). Entity-hydration-free
   * for the {@link LibraryFile} row itself — same reasoning as {@code MediaItemDeletionService}'s
   * own doc comment: loading it and then bulk-deleting its child rows in the same transaction is
   * exactly the pattern that surfaced a real Hibernate bug there.
   */
  @Transactional
  public boolean delete(UUID fileId, boolean deleteFromDisk) {
    Object[] row =
        em.createQuery(
                "select f.path, f.mediaItem.id, f.sizeBytes from LibraryFile f where f.id = :id",
                Object[].class)
            .setParameter("id", fileId)
            .getResultStream()
            .findFirst()
            .orElse(null);
    if (row == null) {
      return false;
    }
    String path = (String) row[0];
    UUID mediaItemId = (UUID) row[1];
    long sizeBytes = (Long) row[2];

    LibraryFileHash.delete("libraryFile.id", fileId);
    LibraryFileEmbeddedId.delete("libraryFile.id", fileId);
    LibraryFileTrack.delete("libraryFile.id", fileId);
    LibraryFile.deleteById(fileId);

    if (deleteFromDisk) {
      deleteFromDisk(path);
    }
    if (mediaItemId != null) {
      MediaItem.<MediaItem>findByIdOptional(mediaItemId)
          .ifPresent(
              mediaItem ->
                  historyEventService.record(
                      mediaItem, "FILE_DELETED", "Removed " + path + ".", sizeBytes));
    }
    return true;
  }

  /**
   * Points an already-imported file at a different title — e.g. it was matched to the wrong
   * edition/cut. Pins the match ({@code matchPinned = true}) so a later sync/re-scan doesn't
   * silently move it back.
   */
  @Transactional
  public Optional<LibraryFile> rematch(UUID fileId, UUID newMediaItemId) {
    Optional<LibraryFile> file = LibraryFile.findByIdOptional(fileId);
    Optional<MediaItem> mediaItem = MediaItem.findByIdOptional(newMediaItemId);
    if (file.isEmpty() || mediaItem.isEmpty()) {
      return Optional.empty();
    }
    LibraryFile f = file.get();
    f.mediaItem = mediaItem.get();
    f.matchMethod = "MANUAL";
    f.matchConfidence = 1.0f;
    f.matchPinned = true;
    f.matchedAt = Instant.now();
    return Optional.of(f);
  }

  /**
   * What this file would be renamed to under the naming settings as configured right now — read
   * requiring no mutation, so unlike {@link #delete}/{@link #rematch} this one loads the {@link
   * LibraryFile} entity normally (no bulk operation follows it in the same transaction, so none of
   * the entity-hydration caveats those two carry apply here).
   */
  public Optional<PreviewRenameResult> previewRename(UUID fileId) {
    return LibraryFile.<LibraryFile>findByIdOptional(fileId)
        .map(
            file -> {
              String targetPath =
                  importService.previewTargetPath(file.mediaItem, file.path).toString();
              return new PreviewRenameResult(file.path, targetPath, !targetPath.equals(file.path));
            });
  }

  /** Best-effort — a missing/permission-denied file must never abort the rest of the delete. */
  private void deleteFromDisk(String path) {
    try {
      Files.deleteIfExists(Path.of(path));
    } catch (IOException | RuntimeException e) {
      // Left on disk; the LibraryFile row referencing it is gone either way.
    }
  }
}

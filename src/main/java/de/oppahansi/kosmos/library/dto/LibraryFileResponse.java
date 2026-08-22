package de.oppahansi.kosmos.library.dto;

import de.oppahansi.kosmos.library.LibraryFile;
import java.time.Instant;
import java.util.UUID;

public record LibraryFileResponse(
    UUID id,
    UUID mediaItemId,
    String path,
    long sizeBytes,
    String matchMethod,
    float matchConfidence,
    boolean matchPinned,
    Instant importedAt,
    boolean verified,
    Instant probedAt,
    String embeddedTitle,
    String container,
    String videoCodec,
    Integer resolutionWidth,
    Integer resolutionHeight,
    Integer durationSeconds,
    String hdrFormat,
    Integer bitDepth) {

  public static LibraryFileResponse from(LibraryFile file) {
    return new LibraryFileResponse(
        file.id,
        file.mediaItem.id,
        file.path,
        file.sizeBytes,
        file.matchMethod,
        file.matchConfidence,
        file.matchPinned,
        file.importedAt,
        file.verified,
        file.probedAt,
        file.embeddedTitle,
        file.container,
        file.videoCodec,
        file.resolutionWidth,
        file.resolutionHeight,
        file.durationSeconds,
        file.hdrFormat,
        file.bitDepth);
  }
}

package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.Show;
import java.time.Instant;
import java.util.UUID;

/** Summary shape for listing shows — no season/episode tree, see {@link ShowDetailResponse}. */
public record ShowResponse(
    UUID id,
    String title,
    Integer year,
    String overview,
    String posterPath,
    String backdropPath,
    String status,
    Instant addedAt,
    UUID qualityProfileId,
    boolean partiallyAvailable,
    UUID rootFolderId) {

  public static ShowResponse from(Show show, boolean partiallyAvailable) {
    return new ShowResponse(
        show.mediaItemId,
        show.mediaItem.title,
        show.mediaItem.year,
        show.overview,
        show.posterPath,
        show.backdropPath,
        show.status,
        show.mediaItem.addedAt,
        show.qualityProfile == null ? null : show.qualityProfile.id,
        partiallyAvailable,
        show.mediaItem.rootFolder == null ? null : show.mediaItem.rootFolder.id);
  }
}

package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.Show;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ShowDetailResponse(
    UUID id,
    String title,
    Integer year,
    String overview,
    String posterPath,
    String backdropPath,
    String status,
    Instant addedAt,
    UUID qualityProfileId,
    Boolean seasonFolderEnabled,
    List<SeasonResponse> seasons,
    UUID rootFolderId) {

  public static ShowDetailResponse from(Show show, List<SeasonResponse> seasons) {
    return new ShowDetailResponse(
        show.mediaItemId,
        show.mediaItem.title,
        show.mediaItem.year,
        show.overview,
        show.posterPath,
        show.backdropPath,
        show.status,
        show.mediaItem.addedAt,
        show.qualityProfile == null ? null : show.qualityProfile.id,
        show.seasonFolderEnabled,
        seasons,
        show.mediaItem.rootFolder == null ? null : show.mediaItem.rootFolder.id);
  }
}

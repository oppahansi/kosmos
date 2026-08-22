package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.Movie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** API representation of a {@link Movie}. */
public record MovieResponse(
    UUID id,
    String title,
    Integer year,
    Integer runtimeMinutes,
    String overview,
    String posterPath,
    String backdropPath,
    Instant addedAt,
    UUID qualityProfileId,
    LocalDate releaseDate,
    LocalDate digitalReleaseDate,
    String minimumAvailability,
    UUID rootFolderId) {

  public static MovieResponse from(Movie movie) {
    return new MovieResponse(
        movie.mediaItemId,
        movie.mediaItem.title,
        movie.mediaItem.year,
        movie.runtimeMinutes,
        movie.overview,
        movie.posterPath,
        movie.backdropPath,
        movie.mediaItem.addedAt,
        movie.qualityProfile == null ? null : movie.qualityProfile.id,
        movie.releaseDate,
        movie.digitalReleaseDate,
        movie.minimumAvailability,
        movie.mediaItem.rootFolder == null ? null : movie.mediaItem.rootFolder.id);
  }
}

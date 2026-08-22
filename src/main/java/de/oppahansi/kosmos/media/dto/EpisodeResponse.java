package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.Episode;
import java.time.LocalDate;
import java.util.UUID;

/** {@code status} is one of MISSING / GRABBED / IMPORTED / AVAILABLE — see {@code ShowResource}. */
public record EpisodeResponse(
    UUID id,
    int episodeNumber,
    String title,
    String overview,
    LocalDate airDate,
    Integer runtimeMinutes,
    String stillPath,
    boolean monitored,
    String status) {

  public static EpisodeResponse from(Episode episode, String status) {
    return new EpisodeResponse(
        episode.mediaItemId,
        episode.episodeNumber,
        episode.mediaItem.title,
        episode.overview,
        episode.airDate,
        episode.runtimeMinutes,
        episode.stillPath,
        episode.monitored,
        status);
  }
}

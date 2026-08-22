package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.AnimeEpisode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code status} is one of MISSING / GRABBED / IMPORTED / AVAILABLE — see {@code AnimeResource}.
 */
public record AnimeEpisodeResponse(
    UUID id,
    Integer episodeNumber,
    Integer absoluteEpisodeNumber,
    String episodeType,
    String title,
    String overview,
    LocalDate airDate,
    Integer runtimeMinutes,
    String stillPath,
    boolean monitored,
    String status) {

  public static AnimeEpisodeResponse from(AnimeEpisode episode, String status) {
    return new AnimeEpisodeResponse(
        episode.mediaItemId,
        episode.episodeNumber,
        episode.absoluteEpisodeNumber,
        episode.episodeType,
        episode.mediaItem.title,
        episode.overview,
        episode.airDate,
        episode.runtimeMinutes,
        episode.stillPath,
        episode.monitored,
        status);
  }
}

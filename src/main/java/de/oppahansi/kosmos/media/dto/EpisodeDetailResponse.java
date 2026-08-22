package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.Episode;
import java.util.UUID;

/**
 * Enough context for the interactive-search/manual-grab pages to work against an episode without
 * fetching the whole show tree — {@code title} is the episode's own title, {@code showTitle} +
 * {@code seasonNumber}/{@code episodeNumber} are what the search query itself is built from.
 */
public record EpisodeDetailResponse(
    UUID id,
    int episodeNumber,
    String title,
    Integer runtimeMinutes,
    UUID showId,
    String showTitle,
    int seasonNumber,
    UUID qualityProfileId,
    boolean monitored,
    String status) {

  public static EpisodeDetailResponse from(Episode episode, String status) {
    return new EpisodeDetailResponse(
        episode.mediaItemId,
        episode.episodeNumber,
        episode.mediaItem.title,
        episode.runtimeMinutes,
        episode.season.show.mediaItemId,
        episode.season.show.mediaItem.title,
        episode.season.seasonNumber,
        episode.season.show.qualityProfile == null ? null : episode.season.show.qualityProfile.id,
        episode.monitored,
        status);
  }
}

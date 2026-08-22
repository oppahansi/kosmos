package de.oppahansi.kosmos.media.dto;

import de.oppahansi.kosmos.media.AnimeEpisode;
import java.util.UUID;

/**
 * Enough context for the interactive-search/manual-grab pages to work against an anime episode
 * without fetching the whole anime tree — mirrors {@link EpisodeDetailResponse}. {@code
 * absoluteEpisodeNumber} is what the search query is built from (see {@link AnimeEpisode}'s own doc
 * comment on why it, not {@code episodeNumber}, matches fansub filenames).
 */
public record AnimeEpisodeDetailResponse(
    UUID id,
    Integer episodeNumber,
    Integer absoluteEpisodeNumber,
    String title,
    Integer runtimeMinutes,
    UUID animeId,
    String animeTitle,
    UUID qualityProfileId,
    boolean monitored,
    String status) {

  public static AnimeEpisodeDetailResponse from(AnimeEpisode episode, String status) {
    return new AnimeEpisodeDetailResponse(
        episode.mediaItemId,
        episode.episodeNumber,
        episode.absoluteEpisodeNumber,
        episode.mediaItem.title,
        episode.runtimeMinutes,
        episode.season.anime.mediaItemId,
        episode.season.anime.mediaItem.title,
        episode.season.anime.qualityProfile == null ? null : episode.season.anime.qualityProfile.id,
        episode.monitored,
        status);
  }
}

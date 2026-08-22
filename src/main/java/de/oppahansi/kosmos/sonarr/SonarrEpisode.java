package de.oppahansi.kosmos.sonarr;

/**
 * One episode as reported by {@code GET /api/v3/episode?seriesId=&includeEpisodeFile=true} — {@code
 * path} is null unless {@code hasFile} is true, same convention as {@link SonarrSeries}.
 */
public record SonarrEpisode(Integer seasonNumber, Integer episodeNumber, String path) {}

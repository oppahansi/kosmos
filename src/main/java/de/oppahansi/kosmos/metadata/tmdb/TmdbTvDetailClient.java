package de.oppahansi.kosmos.metadata.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.cache.PersistentCache;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The per-season/per-details half of {@link TmdbMetadataProvider#fetchShowStructure} — split into
 * its own bean specifically so each piece can be cached independently. {@code @CacheResult} is a
 * CDI interceptor: it only fires on calls that go through a bean's proxy, so when these two methods
 * lived as private helpers called via plain {@code this.} inside {@code TmdbMetadataProvider},
 * annotating them would have silently done nothing (the self-invocation gap). Calling them here,
 * through injection, is a genuine cross-bean call.
 *
 * <p>This buys something {@code fetchShowStructure}'s own whole-tree cache can't: if a show's fetch
 * fails partway through (say season 4 of 6), nothing gets cached at {@code fetchShowStructure}'s
 * level — {@code @CacheResult} never caches a thrown exception — so a retry calls it again from
 * scratch. With these cached individually, that retry still gets cache hits for seasons 1–3
 * (already fetched successfully last time) and only re-fetches the one that actually failed.
 */
@ApplicationScoped
class TmdbTvDetailClient {

  private static final String TV_URL = "https://api.themoviedb.org/3/tv/";

  @ConfigProperty(name = "kosmos.metadata.tmdb.api-key")
  Optional<String> apiKey;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @CacheResult(cacheName = "tmdb-show-details")
  @PersistentCache
  TmdbTvDetails fetchTvDetails(String tmdbId) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(TV_URL + tmdbId + "?api_key=" + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("TMDB show details failed: HTTP " + response.statusCode());
      }
      return objectMapper.readValue(response.body(), TmdbTvDetails.class);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("Failed to fetch TMDB show details", e);
    }
  }

  @CacheResult(cacheName = "tmdb-season-episodes")
  @PersistentCache
  List<TmdbShowStructure.EpisodeData> fetchSeasonEpisodes(String tmdbId, int seasonNumber) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      TV_URL
                          + tmdbId
                          + "/season/"
                          + seasonNumber
                          + "?api_key="
                          + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return List.of();
      }
      TmdbSeasonDetails details = objectMapper.readValue(response.body(), TmdbSeasonDetails.class);
      return details.episodes().stream().map(this::toEpisodeData).toList();
    } catch (IOException | InterruptedException e) {
      return List.of();
    }
  }

  private TmdbShowStructure.EpisodeData toEpisodeData(TmdbSeasonDetails.Episode episode) {
    return new TmdbShowStructure.EpisodeData(
        episode.episodeNumber(),
        episode.name(),
        episode.overview(),
        parseDate(episode.airDate()),
        episode.runtime(),
        episode.stillPath());
  }

  private LocalDate parseDate(String date) {
    if (date == null || date.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(date);
    } catch (Exception e) {
      return null;
    }
  }
}

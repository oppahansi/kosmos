package de.oppahansi.kosmos.sonarr;

import com.fasterxml.jackson.databind.JsonNode;
import de.oppahansi.kosmos.arr.ArrClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Thin client for the Sonarr v3 API — never mutates anything server-side, X-Api-Key auth. */
public class SonarrClient extends ArrClient {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  public SonarrClient(String baseUrl, String apiKey) {
    super(baseUrl, apiKey, "Sonarr");
  }

  /** Every series Sonarr knows about, monitored or not — file presence is checked per-episode. */
  public List<SonarrSeries> listSeries() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/api/v3/series");
    checkOk(response, "list series");
    JsonNode root = mapper().readTree(response.body());

    List<SonarrSeries> series = new ArrayList<>();
    for (JsonNode item : root) {
      Integer year = item.hasNonNull("year") ? item.path("year").asInt() : null;
      String tmdbId =
          item.hasNonNull("tmdbId") ? String.valueOf(item.path("tmdbId").asLong()) : null;
      String tvdbId =
          item.hasNonNull("tvdbId") ? String.valueOf(item.path("tvdbId").asLong()) : null;
      series.add(
          new SonarrSeries(
              item.path("id").asInt(),
              item.path("title").asText(null),
              year,
              tmdbId,
              tvdbId,
              item.path("path").asText(null)));
    }
    return series;
  }

  /**
   * A single series' episodes, with the on-disk path already embedded when the episode has a file
   * ({@code includeEpisodeFile=true} — without it, Sonarr's episode list omits the file path even
   * for episodes it has one for).
   */
  public List<SonarrEpisode> listEpisodes(int seriesId) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(
                URI.create(
                    baseUrl + "/api/v3/episode?seriesId=" + seriesId + "&includeEpisodeFile=true"))
            .header("X-Api-Key", apiKey)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    checkOk(response, "list episodes");
    JsonNode root = mapper().readTree(response.body());

    List<SonarrEpisode> episodes = new ArrayList<>();
    for (JsonNode item : root) {
      if (!item.path("hasFile").asBoolean(false)) {
        continue;
      }
      Integer season = item.hasNonNull("seasonNumber") ? item.path("seasonNumber").asInt() : null;
      Integer episodeNumber =
          item.hasNonNull("episodeNumber") ? item.path("episodeNumber").asInt() : null;
      String path = item.path("episodeFile").path("path").asText(null);
      episodes.add(new SonarrEpisode(season, episodeNumber, path));
    }
    return episodes;
  }
}

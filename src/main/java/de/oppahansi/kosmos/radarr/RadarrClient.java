package de.oppahansi.kosmos.radarr;

import com.fasterxml.jackson.databind.JsonNode;
import de.oppahansi.kosmos.arr.ArrClient;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** Thin client for the Radarr v3 API — never mutates anything server-side, X-Api-Key auth. */
public class RadarrClient extends ArrClient {

  public RadarrClient(String baseUrl, String apiKey) {
    super(baseUrl, apiKey, "Radarr");
  }

  /**
   * Every movie Radarr knows about, monitored or not. {@link RadarrMovie#hasFile} tells the caller
   * which ones actually have a file on disk today — see {@code RadarrSyncService}'s own doc for why
   * only those are imported.
   */
  public List<RadarrMovie> listMovies() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/api/v3/movie");
    checkOk(response, "list movies");
    JsonNode root = mapper().readTree(response.body());

    List<RadarrMovie> movies = new ArrayList<>();
    for (JsonNode item : root) {
      boolean hasFile = item.path("hasFile").asBoolean(false);
      String path = hasFile ? item.path("movieFile").path("path").asText(null) : null;
      Integer year = item.hasNonNull("year") ? item.path("year").asInt() : null;
      String tmdbId =
          item.hasNonNull("tmdbId") ? String.valueOf(item.path("tmdbId").asLong()) : null;
      movies.add(new RadarrMovie(item.path("title").asText(null), year, tmdbId, hasFile, path));
    }
    return movies;
  }
}

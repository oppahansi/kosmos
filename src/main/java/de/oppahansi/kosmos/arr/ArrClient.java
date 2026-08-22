package de.oppahansi.kosmos.arr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared HTTP mechanics for Radarr and Sonarr — unlike Jellyfin vs. Prowlarr, both Servarr apps
 * expose the identical v3 API shape for what this covers (X-Api-Key auth, {@code
 * /api/v3/system/status} for reachability, {@code /api/v3/rootfolder} for configured folders), so
 * {@link de.oppahansi.kosmos.radarr.RadarrClient}/{@link de.oppahansi.kosmos.sonarr.SonarrClient}
 * use this compositionally rather than each re-implementing the same request plumbing.
 */
public class ArrClient {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

  protected final String baseUrl;
  protected final String apiKey;
  protected final HttpClient httpClient =
      HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  private final String label;

  /**
   * @param label Used only in error messages, e.g. "Radarr"/"Sonarr".
   */
  public ArrClient(String baseUrl, String apiKey, String label) {
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.apiKey = apiKey;
    this.label = label;
  }

  /** Reachability + auth check — a 200 means the base URL and API key are both valid. */
  public void testConnection() throws IOException, InterruptedException {
    checkOk(get("/api/v3/system/status"), "check connection");
  }

  /** Configured root folder paths — the direct analog of Jellyfin's own library Locations. */
  public List<String> listRootFolders() throws IOException, InterruptedException {
    HttpResponse<String> response = get("/api/v3/rootfolder");
    checkOk(response, "list root folders");
    JsonNode root = MAPPER.readTree(response.body());
    List<String> paths = new ArrayList<>();
    for (JsonNode folder : root) {
      String path = folder.path("path").asText(null);
      if (path != null) {
        paths.add(path);
      }
    }
    return paths;
  }

  protected HttpResponse<String> get(String path) throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .header("X-Api-Key", apiKey)
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();
    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
  }

  protected void checkOk(HttpResponse<String> response, String action) throws IOException {
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new IOException(
          "%s returned %d for %s".formatted(label, response.statusCode(), action));
    }
  }

  protected static ObjectMapper mapper() {
    return MAPPER;
  }
}

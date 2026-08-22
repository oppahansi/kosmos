package de.oppahansi.kosmos.plugins;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.cache.PersistentCache;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Lists {@link RegistryEntry}s from the kosmos-plugin-registry GitHub repo via its real contents
 * API — read-only discovery, no installation. Requires the registry repo to be publicly readable;
 * this client sends no credentials.
 */
@ApplicationScoped
public class PluginRegistryClient {

  @ConfigProperty(
      name = "kosmos.plugins.registry.contents-url",
      defaultValue =
          "https://api.github.com/repos/kosmos-suite/kosmos-plugin-registry/contents/plugins")
  String contentsUrl;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @CacheResult(cacheName = "plugin-registry")
  @PersistentCache
  public List<RegistryEntry> listEntries() {
    ContentItem[] files = fetchJson(contentsUrl, ContentItem[].class);
    List<RegistryEntry> entries = new ArrayList<>();
    for (ContentItem file : files) {
      if ("file".equals(file.type()) && file.name().endsWith(".json")) {
        entries.add(fetchJson(file.downloadUrl(), RegistryEntry.class));
      }
    }
    return entries;
  }

  private <T> T fetchJson(String url, Class<T> type) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .header("Accept", "application/vnd.github+json")
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new UncheckedIOException(
            new IOException("GET " + url + " -> " + response.statusCode()));
      }
      return objectMapper.readValue(response.body(), type);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new UncheckedIOException(new IOException(e));
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ContentItem(
      String name, String type, @JsonProperty("download_url") String downloadUrl) {}
}

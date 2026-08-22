package de.oppahansi.kosmos.sonarr.dto;

import de.oppahansi.kosmos.sonarr.SonarrServer;
import java.time.Instant;
import java.util.UUID;

public record SonarrServerResponse(
    UUID id, String name, String baseUrl, boolean apiKeySet, boolean enabled, Instant createdAt) {

  public static SonarrServerResponse from(SonarrServer server) {
    return new SonarrServerResponse(
        server.id,
        server.name,
        server.baseUrl,
        server.apiKey != null && !server.apiKey.isBlank(),
        server.enabled,
        server.createdAt);
  }
}

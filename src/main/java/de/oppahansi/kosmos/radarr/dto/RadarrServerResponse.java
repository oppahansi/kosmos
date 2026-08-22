package de.oppahansi.kosmos.radarr.dto;

import de.oppahansi.kosmos.radarr.RadarrServer;
import java.time.Instant;
import java.util.UUID;

public record RadarrServerResponse(
    UUID id, String name, String baseUrl, boolean apiKeySet, boolean enabled, Instant createdAt) {

  public static RadarrServerResponse from(RadarrServer server) {
    return new RadarrServerResponse(
        server.id,
        server.name,
        server.baseUrl,
        server.apiKey != null && !server.apiKey.isBlank(),
        server.enabled,
        server.createdAt);
  }
}

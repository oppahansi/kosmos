package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.radarr.dto.CreateRadarrServerRequest;
import de.oppahansi.kosmos.radarr.dto.RootFolderAutoRegisterResult;
import de.oppahansi.kosmos.radarr.dto.TestRadarrConnectionResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class RadarrServerService {

  @Inject LibraryRootFolderService rootFolderService;

  public List<RadarrServer> listAll() {
    return RadarrServer.listAll();
  }

  public Optional<RadarrServer> findById(UUID id) {
    return RadarrServer.findByIdOptional(id);
  }

  /**
   * Same reachability/auth check {@link #autoRegisterRootFolders} does for an already-saved server,
   * but against a baseUrl/apiKey pair that hasn't been persisted yet — what the "Add server"
   * modal's Test Connection button calls before Save is enabled.
   */
  public TestRadarrConnectionResult testConnection(String baseUrl, String apiKey) {
    try {
      new RadarrClient(baseUrl, apiKey).testConnection();
      return new TestRadarrConnectionResult(true, "Connected.");
    } catch (IOException | InterruptedException e) {
      return new TestRadarrConnectionResult(
          false, "Could not reach Radarr server: " + e.getMessage());
    }
  }

  @Transactional
  public RadarrServer create(CreateRadarrServerRequest request) {
    RadarrServer server = new RadarrServer();
    server.name = request.name();
    server.baseUrl = request.baseUrl();
    server.apiKey = request.apiKey();
    server.enabled = true;
    server.createdAt = Instant.now();
    server.persist();
    return server;
  }

  /**
   * Registers a root folder per Radarr's own configured root folder path, tagged {@code movie}.
   * Uses {@link LibraryRootFolderService#createTrusted} rather than the normal validating create:
   * Radarr has already vouched for these paths, and Kosmos may not share its filesystem view (a
   * different host, different container mounts) at the moment this runs.
   */
  public RootFolderAutoRegisterResult autoRegisterRootFolders(UUID id) {
    RadarrServer server = requireServer(id);
    List<String> folders;
    try {
      folders = new RadarrClient(server.baseUrl, server.apiKey).listRootFolders();
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Radarr server: " + e.getMessage());
    }

    int registered = 0;
    int skipped = 0;
    for (String folder : folders) {
      if (rootFolderService.createTrusted(folder, List.of("movie")).isPresent()) {
        registered++;
      } else {
        skipped++;
      }
    }
    return new RootFolderAutoRegisterResult(registered, skipped);
  }

  RadarrServer requireServer(UUID id) {
    return findById(id).orElseThrow(() -> new NotFoundException("Unknown Radarr server id: " + id));
  }
}

package de.oppahansi.kosmos.sonarr;

import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.sonarr.dto.CreateSonarrServerRequest;
import de.oppahansi.kosmos.sonarr.dto.RootFolderAutoRegisterResult;
import de.oppahansi.kosmos.sonarr.dto.TestSonarrConnectionResult;
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
public class SonarrServerService {

  @Inject LibraryRootFolderService rootFolderService;

  public List<SonarrServer> listAll() {
    return SonarrServer.listAll();
  }

  public Optional<SonarrServer> findById(UUID id) {
    return SonarrServer.findByIdOptional(id);
  }

  public TestSonarrConnectionResult testConnection(String baseUrl, String apiKey) {
    try {
      new SonarrClient(baseUrl, apiKey).testConnection();
      return new TestSonarrConnectionResult(true, "Connected.");
    } catch (IOException | InterruptedException e) {
      return new TestSonarrConnectionResult(
          false, "Could not reach Sonarr server: " + e.getMessage());
    }
  }

  @Transactional
  public SonarrServer create(CreateSonarrServerRequest request) {
    SonarrServer server = new SonarrServer();
    server.name = request.name();
    server.baseUrl = request.baseUrl();
    server.apiKey = request.apiKey();
    server.enabled = true;
    server.createdAt = Instant.now();
    server.persist();
    return server;
  }

  /**
   * Registers a root folder per Sonarr's own configured root folder path, tagged {@code show} and
   * {@code anime} — Sonarr, like Jellyfin's own "tvshows" collection type, doesn't distinguish the
   * two at the root-folder level (a user's own "/anime" vs "/shows" root folders are both just
   * Sonarr root folders), so both content types are accepted from either one. Uses {@link
   * LibraryRootFolderService#createTrusted} since Sonarr has already vouched for these paths.
   */
  public RootFolderAutoRegisterResult autoRegisterRootFolders(UUID id) {
    SonarrServer server = requireServer(id);
    List<String> folders;
    try {
      folders = new SonarrClient(server.baseUrl, server.apiKey).listRootFolders();
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Sonarr server: " + e.getMessage());
    }

    int registered = 0;
    int skipped = 0;
    for (String folder : folders) {
      if (rootFolderService.createTrusted(folder, List.of("show", "anime")).isPresent()) {
        registered++;
      } else {
        skipped++;
      }
    }
    return new RootFolderAutoRegisterResult(registered, skipped);
  }

  SonarrServer requireServer(UUID id) {
    return findById(id).orElseThrow(() -> new NotFoundException("Unknown Sonarr server id: " + id));
  }
}

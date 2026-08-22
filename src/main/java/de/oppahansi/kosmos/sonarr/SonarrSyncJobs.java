package de.oppahansi.kosmos.sonarr;

import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.JobHandlerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link SonarrLibrarySyncJob} per enabled {@link SonarrServer} — mirrors {@code
 * JellyfinSyncJobs}.
 */
@ApplicationScoped
public class SonarrSyncJobs implements JobHandlerFactory {

  @Inject SonarrSyncService syncService;

  @Override
  public List<JobHandler> currentHandlers() {
    return SonarrServer.<SonarrServer>list("enabled", true).stream()
        .map(server -> (JobHandler) new SonarrLibrarySyncJob(server.id, server.name, syncService))
        .toList();
  }

  public Optional<JobHandler> libraryJobForServer(UUID serverId) {
    return SonarrServer.<SonarrServer>findByIdOptional(serverId)
        .map(server -> new SonarrLibrarySyncJob(server.id, server.name, syncService));
  }
}

package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.JobHandlerFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link RadarrLibrarySyncJob} per enabled {@link RadarrServer} — mirrors {@code
 * JellyfinSyncJobs}.
 */
@ApplicationScoped
public class RadarrSyncJobs implements JobHandlerFactory {

  @Inject RadarrSyncService syncService;

  @Override
  public List<JobHandler> currentHandlers() {
    return RadarrServer.<RadarrServer>list("enabled", true).stream()
        .map(server -> (JobHandler) new RadarrLibrarySyncJob(server.id, server.name, syncService))
        .toList();
  }

  public Optional<JobHandler> libraryJobForServer(UUID serverId) {
    return RadarrServer.<RadarrServer>findByIdOptional(serverId)
        .map(server -> new RadarrLibrarySyncJob(server.id, server.name, syncService));
  }
}

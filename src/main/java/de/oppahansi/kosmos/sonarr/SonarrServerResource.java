package de.oppahansi.kosmos.sonarr;

import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.JobRunner;
import de.oppahansi.kosmos.scheduler.dto.JobRunResponse;
import de.oppahansi.kosmos.sonarr.dto.CreateSonarrServerRequest;
import de.oppahansi.kosmos.sonarr.dto.RootFolderAutoRegisterResult;
import de.oppahansi.kosmos.sonarr.dto.SonarrServerResponse;
import de.oppahansi.kosmos.sonarr.dto.TestSonarrConnectionRequest;
import de.oppahansi.kosmos.sonarr.dto.TestSonarrConnectionResult;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Path("/sonarr-servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SonarrServerResource {

  @Inject SonarrServerService serverService;
  @Inject SonarrSyncJobs syncJobs;
  @Inject JobRunner jobRunner;

  @GET
  public List<SonarrServerResponse> list() {
    return serverService.listAll().stream().map(SonarrServerResponse::from).toList();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return serverService
        .findById(id)
        .map(server -> Response.ok(SonarrServerResponse.from(server)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @POST
  public Response create(CreateSonarrServerRequest request) {
    SonarrServerResponse response = SonarrServerResponse.from(serverService.create(request));
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @POST
  @Path("/test-connection")
  public TestSonarrConnectionResult testConnection(TestSonarrConnectionRequest request) {
    return serverService.testConnection(request.baseUrl(), request.apiKey());
  }

  /**
   * Runs this server's {@link SonarrLibrarySyncJob} immediately — routed through {@link JobRunner}
   * rather than {@link SonarrSyncService} directly so a manual run is recorded in job history
   * exactly like a scheduled one.
   */
  @POST
  @Path("/{id}/sync-library")
  public Response syncLibrary(@PathParam("id") UUID id) {
    Optional<JobHandler> handler = syncJobs.libraryJobForServer(id);
    if (handler.isEmpty()) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return jobRunner
        .runNow(handler.get())
        .map(run -> Response.ok(JobRunResponse.from(run)).build())
        .orElse(Response.status(Response.Status.CONFLICT).build());
  }

  @POST
  @Path("/{id}/root-folders")
  public RootFolderAutoRegisterResult autoRegisterRootFolders(@PathParam("id") UUID id) {
    return serverService.autoRegisterRootFolders(id);
  }
}

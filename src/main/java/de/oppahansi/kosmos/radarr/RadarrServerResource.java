package de.oppahansi.kosmos.radarr;

import de.oppahansi.kosmos.radarr.dto.CreateRadarrServerRequest;
import de.oppahansi.kosmos.radarr.dto.RadarrServerResponse;
import de.oppahansi.kosmos.radarr.dto.RootFolderAutoRegisterResult;
import de.oppahansi.kosmos.radarr.dto.TestRadarrConnectionRequest;
import de.oppahansi.kosmos.radarr.dto.TestRadarrConnectionResult;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.JobRunner;
import de.oppahansi.kosmos.scheduler.dto.JobRunResponse;
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

@Path("/radarr-servers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RadarrServerResource {

  @Inject RadarrServerService serverService;
  @Inject RadarrSyncJobs syncJobs;
  @Inject JobRunner jobRunner;

  @GET
  public List<RadarrServerResponse> list() {
    return serverService.listAll().stream().map(RadarrServerResponse::from).toList();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return serverService
        .findById(id)
        .map(server -> Response.ok(RadarrServerResponse.from(server)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @POST
  public Response create(CreateRadarrServerRequest request) {
    RadarrServerResponse response = RadarrServerResponse.from(serverService.create(request));
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  /**
   * Checked before a server is persisted — what the "Add server" modal's Test Connection button
   * calls. A {@link jakarta.ws.rs.BadRequestException} never surfaces here; failure is carried in
   * the result body instead, same as Jellyfin's own test-connection endpoint.
   */
  @POST
  @Path("/test-connection")
  public TestRadarrConnectionResult testConnection(TestRadarrConnectionRequest request) {
    return serverService.testConnection(request.baseUrl(), request.apiKey());
  }

  /**
   * Runs this server's {@link RadarrLibrarySyncJob} immediately — routed through {@link JobRunner}
   * rather than {@link RadarrSyncService} directly so a manual run is recorded in job history
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

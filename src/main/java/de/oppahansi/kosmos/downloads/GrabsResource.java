package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.downloads.dto.GrabResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

/**
 * Grab-id-scoped operations, separate from {@link GrabResource}/{@code EpisodeGrabResource} (which
 * stay scoped under their own movie/episode paths for creation) — listing and failure-marking act
 * on the grab itself, not on any one media item.
 */
@Path("/grabs")
@Produces(MediaType.APPLICATION_JSON)
public class GrabsResource {

  @Inject GrabService grabService;

  @GET
  public List<GrabResponse> list() {
    return Grab.<Grab>list("order by grabbedAt desc").stream()
        .map(this::toResponseWithLiveProgress)
        .toList();
  }

  /**
   * Best-effort live progress for a still-in-flight grab — the same client call {@code
   * DownloadStatusPollJob} makes every 30s, just synchronous here so the Activity queue reflects
   * current state on demand rather than only every poll tick.
   */
  private GrabResponse toResponseWithLiveProgress(Grab grab) {
    if (!"GRABBED".equals(grab.status) || grab.jobId == null) {
      return GrabResponse.from(grab);
    }
    try {
      TorrentClient client = TorrentClients.forConfig(grab.downloadClient);
      if (!client.login(grab.downloadClient.username, grab.downloadClient.password)) {
        return GrabResponse.from(grab);
      }
      return client
          .getTorrentInfo(grab.jobId)
          .map(status -> GrabResponse.from(grab, status.progress() * 100))
          .orElseGet(() -> GrabResponse.from(grab));
    } catch (Exception e) {
      return GrabResponse.from(grab);
    }
  }

  @POST
  @Path("/{id}/mark-failed")
  public Response markFailed(@PathParam("id") UUID id) {
    return grabService
        .markFailed(id)
        .map(grab -> Response.ok(GrabResponse.from(grab)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }
}

package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.downloads.Grab;
import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.media.dto.EpisodeDetailResponse;
import de.oppahansi.kosmos.media.dto.UpdateMonitoredRequest;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/** Backs the interactive-search/manual-grab pages when their target is an episode, not a movie. */
@Path("/episodes")
@Produces(MediaType.APPLICATION_JSON)
public class EpisodeResource {

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return Episode.<Episode>findByIdOptional(id)
        .map(episode -> Response.ok(EpisodeDetailResponse.from(episode, statusFor(id))).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @PUT
  @Path("/{id}/monitored")
  @Consumes(MediaType.APPLICATION_JSON)
  @Transactional
  public Response updateMonitored(@PathParam("id") UUID id, UpdateMonitoredRequest request) {
    return Episode.<Episode>findByIdOptional(id)
        .map(
            episode -> {
              episode.monitored = request.monitored();
              return Response.ok(EpisodeDetailResponse.from(episode, statusFor(id))).build();
            })
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  private String statusFor(UUID episodeMediaItemId) {
    if (LibraryFile.count("mediaItem.id", episodeMediaItemId) > 0) {
      return "AVAILABLE";
    }
    Grab grab =
        Grab.<Grab>find("release.mediaItem.id = ?1 order by grabbedAt desc", episodeMediaItemId)
            .firstResult();
    return grab != null ? grab.status : "MISSING";
  }
}

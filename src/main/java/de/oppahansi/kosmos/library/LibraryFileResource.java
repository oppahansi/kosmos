package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.auth.CurrentUser;
import de.oppahansi.kosmos.library.dto.LibraryFileResponse;
import de.oppahansi.kosmos.library.dto.RematchLibraryFileRequest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

@Path("/library-files")
@Produces(MediaType.APPLICATION_JSON)
public class LibraryFileResource {

  @Inject ProbeService probeService;
  @Inject LibraryFileService libraryFileService;
  @Inject CurrentUser currentUser;

  @POST
  @Path("/{id}/probe")
  @Transactional
  public Response probe(@PathParam("id") UUID id) {
    return LibraryFile.<LibraryFile>findByIdOptional(id)
        .map(
            file -> {
              probeService.probe(file);
              return Response.ok(LibraryFileResponse.from(file)).build();
            })
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * {@code deleteFromDisk=true} also removes the file itself (best-effort); default is to remove
   * Kosmos's own record only.
   */
  @DELETE
  @Path("/{id}")
  public Response delete(
      @PathParam("id") UUID id, @QueryParam("deleteFromDisk") boolean deleteFromDisk) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return libraryFileService.delete(id, deleteFromDisk)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /** Re-matches this file to a different title — e.g. it landed on the wrong edition/cut. */
  @PUT
  @Path("/{id}/media-item")
  @Consumes(MediaType.APPLICATION_JSON)
  public Response rematch(@PathParam("id") UUID id, RematchLibraryFileRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return libraryFileService
        .rematch(id, request.mediaItemId())
        .map(file -> Response.ok(LibraryFileResponse.from(file)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }
}

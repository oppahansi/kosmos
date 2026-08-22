package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.jellyfin.UnclassifiedShow;
import de.oppahansi.kosmos.library.dto.CreateLibraryRootFolderRequest;
import de.oppahansi.kosmos.library.dto.LibraryChangeEvent;
import de.oppahansi.kosmos.library.dto.LibraryRootFolderResponse;
import de.oppahansi.kosmos.library.dto.LibraryStatsResponse;
import de.oppahansi.kosmos.media.MediaItem;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.File;
import java.util.List;
import java.util.UUID;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/library")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LibraryResource {

  @Inject LibraryRootFolderService rootFolderService;
  @Inject LibraryChangeBroadcaster libraryChangeBroadcaster;

  @GET
  @Path("/stats")
  public LibraryStatsResponse stats() {
    long movieCount = MediaItem.count("contentType = ?1", "movie");
    long seriesCount = MediaItem.count("contentType = ?1", "show");
    long animeCount = MediaItem.count("contentType = ?1", "anime");
    long needsReviewCount = UnclassifiedShow.count();
    long usedBytes =
        LibraryFile.getEntityManager()
            .createQuery("select coalesce(sum(f.sizeBytes), 0) from LibraryFile f", Long.class)
            .getSingleResult();
    Long totalBytes =
        rootFolderService.getDefault().map(f -> totalSpaceOrNull(f.path)).orElse(null);
    return new LibraryStatsResponse(
        movieCount, seriesCount, animeCount, needsReviewCount, usedBytes, totalBytes);
  }

  /**
   * Live "a title was created or updated" pointer stream — SSE, not polling; see {@link
   * LibraryChangeBroadcaster}'s own doc. {@code @Blocking}: same reasoning as {@code
   * JobResource#progress} — {@link de.oppahansi.kosmos.auth.SessionFilter} does a blocking
   * Hibernate lookup ahead of every request, which needs a worker thread, not the I/O thread a
   * {@code Multi}-returning endpoint runs on by default.
   */
  @GET
  @Path("/changes")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  @Blocking
  public Multi<LibraryChangeEvent> changes() {
    return libraryChangeBroadcaster.subscribe();
  }

  @GET
  @Path("/root-folders")
  public List<LibraryRootFolderResponse> listRootFolders() {
    return rootFolderService.listAll().stream().map(LibraryRootFolderResponse::from).toList();
  }

  @POST
  @Path("/root-folders")
  public Response createRootFolder(CreateLibraryRootFolderRequest request) {
    LibraryRootFolderResponse response =
        LibraryRootFolderResponse.from(
            rootFolderService.create(request.path(), request.contentTypes()));
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @DELETE
  @Path("/root-folders/{id}")
  public Response deleteRootFolder(@PathParam("id") UUID id) {
    rootFolderService.delete(id);
    return Response.noContent().build();
  }

  private Long totalSpaceOrNull(String rootPath) {
    File dir = new File(rootPath);
    return dir.exists() ? Long.valueOf(dir.getTotalSpace()) : null;
  }
}

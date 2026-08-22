package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.auth.CurrentUser;
import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.library.RefreshScanService;
import de.oppahansi.kosmos.library.dto.LibraryFileResponse;
import de.oppahansi.kosmos.library.dto.RefreshScanResult;
import de.oppahansi.kosmos.media.dto.CreateMovieRequest;
import de.oppahansi.kosmos.media.dto.MovieResponse;
import de.oppahansi.kosmos.media.dto.UpdateMinimumAvailabilityRequest;
import de.oppahansi.kosmos.media.dto.UpdateMovieQualityProfileRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

@Path("/movies")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MediaResource {

  @Inject MovieService movieService;
  @Inject MediaItemDeletionService deletionService;
  @Inject RefreshScanService refreshScanService;
  @Inject CurrentUser currentUser;

  @GET
  public List<MovieResponse> list() {
    return movieService.listAll().stream().map(MovieResponse::from).toList();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return movieService
        .findById(id)
        .map(movie -> Response.ok(MovieResponse.from(movie)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Direct add is admin-only — non-admins go through {@code RequestResource} for review instead.
   */
  @POST
  public Response create(CreateMovieRequest request) {
    requireAdmin();
    MovieResponse response = MovieResponse.from(movieService.create(request));
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  /** Backs {@code MovieDetailPage}'s file/probe section — see {@code LibraryFile}. */
  @GET
  @Path("/{id}/library-files")
  public List<LibraryFileResponse> libraryFiles(@PathParam("id") UUID id) {
    return LibraryFile.<LibraryFile>list("mediaItem.id", id).stream()
        .map(LibraryFileResponse::from)
        .toList();
  }

  /** Backs {@code MovieDetailPage}'s Cast/Details/More Like This sections. */
  @GET
  @Path("/{id}/detail-extras")
  public Response detailExtras(@PathParam("id") UUID id) {
    return movieService
        .detailExtras(id)
        .map(extras -> Response.ok(extras).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Backs {@code MediaPreviewPage} — a TMDB movie not yet in the library, reached from a
   * not-in-library card instead of falling back to a search.
   */
  @GET
  @Path("/tmdb/{externalId}")
  public Response preview(@PathParam("externalId") String externalId) {
    return movieService
        .preview(externalId)
        .map(preview -> Response.ok(preview).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @PUT
  @Path("/{id}/quality-profile")
  public Response updateQualityProfile(
      @PathParam("id") UUID id, UpdateMovieQualityProfileRequest request) {
    requireAdmin();
    return movieService
        .updateQualityProfile(id, request.qualityProfileId())
        .map(movie -> Response.ok(MovieResponse.from(movie)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @PUT
  @Path("/{id}/minimum-availability")
  public Response updateMinimumAvailability(
      @PathParam("id") UUID id, UpdateMinimumAvailabilityRequest request) {
    requireAdmin();
    MinimumAvailability availability;
    try {
      availability = MinimumAvailability.valueOf(request.minimumAvailability());
    } catch (IllegalArgumentException e) {
      throw new BadRequestException(
          "Unknown minimum availability: " + request.minimumAvailability());
    }
    return movieService
        .updateMinimumAvailability(id, availability)
        .map(movie -> Response.ok(MovieResponse.from(movie)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * {@code deleteFiles=true} also removes each {@link de.oppahansi.kosmos.library.LibraryFile}'s
   * file from disk (best-effort — a missing/permission-denied file never blocks the rest of the
   * delete); default is to remove Kosmos's own records only, leaving files in place.
   */
  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @QueryParam("deleteFiles") boolean deleteFiles) {
    requireAdmin();
    return deletionService.deleteMovie(id, deleteFiles)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /** Re-fetches TMDB metadata and re-scans this movie's own folder for a file not yet known. */
  @POST
  @Path("/{id}/refresh")
  public RefreshScanResult refresh(@PathParam("id") UUID id) {
    requireAdmin();
    return refreshScanService.refreshMovie(id);
  }

  private void requireAdmin() {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
  }
}

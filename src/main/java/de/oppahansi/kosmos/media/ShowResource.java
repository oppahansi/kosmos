package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.auth.CurrentUser;
import de.oppahansi.kosmos.library.RefreshScanService;
import de.oppahansi.kosmos.library.dto.RefreshScanResult;
import de.oppahansi.kosmos.media.dto.CreateShowRequest;
import de.oppahansi.kosmos.media.dto.SeasonResponse;
import de.oppahansi.kosmos.media.dto.ShowDetailResponse;
import de.oppahansi.kosmos.media.dto.ShowResponse;
import de.oppahansi.kosmos.media.dto.UpdateMovieQualityProfileRequest;
import de.oppahansi.kosmos.media.dto.UpdateRootFolderRequest;
import de.oppahansi.kosmos.media.dto.UpdateSeasonFolderRequest;
import de.oppahansi.kosmos.media.dto.UpdateSeriesMonitoringRequest;
import jakarta.inject.Inject;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/shows")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ShowResource {

  @Inject ShowService showService;
  @Inject MediaItemDeletionService deletionService;
  @Inject RefreshScanService refreshScanService;
  @Inject CurrentUser currentUser;
  @Inject MediaAvailabilityService mediaAvailabilityService;

  @GET
  public List<ShowResponse> list() {
    List<Show> shows = showService.listAll();
    Set<UUID> partialIds =
        mediaAvailabilityService.partiallyAvailableShows(
            shows.stream().map(s -> s.mediaItemId).toList());
    return shows.stream()
        .map(s -> ShowResponse.from(s, partialIds.contains(s.mediaItemId)))
        .toList();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return showService
        .findById(id)
        .map(show -> Response.ok(toDetail(show)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Direct add is admin-only — non-admins go through {@code RequestResource} for review instead.
   */
  @POST
  public Response create(CreateShowRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    ShowResponse response = ShowResponse.from(showService.create(request), false);
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @PUT
  @Path("/{id}/quality-profile")
  public Response updateQualityProfile(
      @PathParam("id") UUID id, UpdateMovieQualityProfileRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return showService
        .updateQualityProfile(id, request.qualityProfileId())
        .map(show -> Response.ok(toDetail(show)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @PUT
  @Path("/{id}/root-folder")
  public Response updateRootFolder(@PathParam("id") UUID id, UpdateRootFolderRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return showService
        .updateRootFolder(id, request.rootFolderId())
        .map(show -> Response.ok(toDetail(show)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  @PUT
  @Path("/{id}/season-folder")
  public Response updateSeasonFolder(@PathParam("id") UUID id, UpdateSeasonFolderRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return showService
        .updateSeasonFolderEnabled(id, request.seasonFolderEnabled())
        .map(show -> Response.ok(toDetail(show)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /** Series Monitoring presets — see {@code ShowService#updateMonitoring}'s own doc. */
  @PUT
  @Path("/{id}/monitoring")
  public Response updateMonitoring(
      @PathParam("id") UUID id, UpdateSeriesMonitoringRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return showService.updateMonitoring(id, request.mode(), request.seasonNumber())
        ? showService
            .findById(id)
            .map(show -> Response.ok(toDetail(show)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND).build())
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /**
   * Re-fetches TMDB metadata (poster/backdrop/overview). No folder scan for shows — see the
   * roadmap's own scope note on why.
   */
  @POST
  @Path("/{id}/refresh")
  public RefreshScanResult refresh(@PathParam("id") UUID id) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return refreshScanService.refreshShow(id);
  }

  /**
   * {@code deleteFiles=true} also removes each episode's file from disk (best-effort); default is
   * to remove Kosmos's own records only.
   */
  @DELETE
  @Path("/{id}")
  public Response delete(@PathParam("id") UUID id, @QueryParam("deleteFiles") boolean deleteFiles) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return deletionService.deleteShow(id, deleteFiles)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /** Backs {@code ShowDetailPage}'s Cast/Details/More Like This sections. */
  @GET
  @Path("/{id}/detail-extras")
  public Response detailExtras(@PathParam("id") UUID id) {
    return showService
        .detailExtras(id)
        .map(extras -> Response.ok(extras).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Backs {@code MediaPreviewPage} — a TMDB show not yet in the library, reached from a
   * not-in-library card instead of falling back to a search.
   */
  @GET
  @Path("/tmdb/{externalId}")
  public Response preview(@PathParam("externalId") String externalId) {
    return showService
        .preview(externalId)
        .map(preview -> Response.ok(preview).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  private ShowDetailResponse toDetail(Show show) {
    List<Season> seasons = showService.seasonsFor(show.mediaItemId);
    Map<UUID, List<Episode>> episodesBySeason = new HashMap<>();
    List<UUID> allEpisodeIds = new ArrayList<>();
    for (Season season : seasons) {
      List<Episode> episodes = showService.episodesFor(season.id);
      episodesBySeason.put(season.id, episodes);
      episodes.forEach(e -> allEpisodeIds.add(e.mediaItemId));
    }
    Map<UUID, String> statusByEpisode = MediaItemStatus.forMediaItems(allEpisodeIds);

    List<SeasonResponse> seasonResponses =
        seasons.stream()
            .map(
                season ->
                    SeasonResponse.from(season, episodesBySeason.get(season.id), statusByEpisode))
            .toList();
    return ShowDetailResponse.from(show, seasonResponses);
  }
}

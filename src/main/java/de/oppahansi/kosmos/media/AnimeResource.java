package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.auth.CurrentUser;
import de.oppahansi.kosmos.media.dto.AnimeDetailResponse;
import de.oppahansi.kosmos.media.dto.AnimeResponse;
import de.oppahansi.kosmos.media.dto.AnimeSeasonResponse;
import de.oppahansi.kosmos.media.dto.CreateAnimeRequest;
import de.oppahansi.kosmos.media.dto.UpdateMovieQualityProfileRequest;
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

@Path("/anime")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AnimeResource {

  @Inject AnimeService animeService;
  @Inject MediaItemDeletionService deletionService;
  @Inject CurrentUser currentUser;
  @Inject MediaAvailabilityService mediaAvailabilityService;

  @GET
  public List<AnimeResponse> list() {
    List<Anime> anime = animeService.listAll();
    Set<UUID> partialIds =
        mediaAvailabilityService.partiallyAvailableAnime(
            anime.stream().map(a -> a.mediaItemId).toList());
    return anime.stream()
        .map(a -> AnimeResponse.from(a, partialIds.contains(a.mediaItemId)))
        .toList();
  }

  @GET
  @Path("/{id}")
  public Response get(@PathParam("id") UUID id) {
    return animeService
        .findById(id)
        .map(anime -> Response.ok(toDetail(anime)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Direct add is admin-only — non-admins go through {@code RequestResource} for review instead.
   */
  @POST
  public Response create(CreateAnimeRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    AnimeResponse response = AnimeResponse.from(animeService.create(request), false);
    return Response.status(Response.Status.CREATED).entity(response).build();
  }

  @PUT
  @Path("/{id}/quality-profile")
  public Response updateQualityProfile(
      @PathParam("id") UUID id, UpdateMovieQualityProfileRequest request) {
    if (!currentUser.isAdmin()) {
      throw new ForbiddenException("Admin only");
    }
    return animeService
        .updateQualityProfile(id, request.qualityProfileId())
        .map(anime -> Response.ok(toDetail(anime)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
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
    return deletionService.deleteAnime(id, deleteFiles)
        ? Response.noContent().build()
        : Response.status(Response.Status.NOT_FOUND).build();
  }

  /** Backs {@code AnimeDetailPage}'s Details/More Like This sections. */
  @GET
  @Path("/{id}/detail-extras")
  public Response detailExtras(@PathParam("id") UUID id) {
    return animeService
        .detailExtras(id)
        .map(extras -> Response.ok(extras).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  /**
   * Backs {@code MediaPreviewPage} — an AniList anime not yet in the library, reached from a
   * not-in-library card instead of falling back to a search.
   */
  @GET
  @Path("/anilist/{externalId}")
  public Response preview(@PathParam("externalId") String externalId) {
    return animeService
        .preview(externalId)
        .map(preview -> Response.ok(preview).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }

  private AnimeDetailResponse toDetail(Anime anime) {
    List<AnimeSeason> seasons = animeService.seasonsFor(anime.mediaItemId);
    Map<UUID, List<AnimeEpisode>> episodesBySeason = new HashMap<>();
    List<UUID> allEpisodeIds = new ArrayList<>();
    for (AnimeSeason season : seasons) {
      List<AnimeEpisode> episodes = animeService.episodesFor(season.id);
      episodesBySeason.put(season.id, episodes);
      episodes.forEach(e -> allEpisodeIds.add(e.mediaItemId));
    }
    Map<UUID, String> statusByEpisode = MediaItemStatus.forMediaItems(allEpisodeIds);

    List<AnimeSeasonResponse> seasonResponses =
        seasons.stream()
            .map(
                season ->
                    AnimeSeasonResponse.from(
                        season, episodesBySeason.get(season.id), statusByEpisode))
            .toList();
    return AnimeDetailResponse.from(anime, seasonResponses);
  }
}

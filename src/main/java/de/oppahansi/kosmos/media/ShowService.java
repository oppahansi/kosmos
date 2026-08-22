package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.library.LibraryRootFolder;
import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.media.dto.CreateShowRequest;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.SimilarEnrichmentService;
import de.oppahansi.kosmos.metadata.dto.MediaDetailExtras;
import de.oppahansi.kosmos.metadata.dto.MediaPreview;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.metadata.tmdb.TmdbShowStructure;
import de.oppahansi.kosmos.parsing.QualityProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ShowService {

  @Inject QualityProfileService qualityProfileService;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject SimilarEnrichmentService similarEnrichmentService;

  public List<Show> listAll() {
    return Show.listAll();
  }

  public Optional<Show> findById(UUID id) {
    return Show.findByIdOptional(id);
  }

  public List<Season> seasonsFor(UUID showId) {
    return Season.list("show.mediaItemId = ?1 order by seasonNumber", showId);
  }

  public List<Episode> episodesFor(UUID seasonId) {
    return Episode.list("season.id = ?1 order by episodeNumber", seasonId);
  }

  /**
   * Unlike {@link MovieService#create}, the TMDB structure fetch here isn't best-effort — a show
   * with no season/episode tree isn't a useful show to have added, so a fetch failure fails the
   * whole creation rather than leaving an empty shell (see
   * TmdbMetadataProvider#fetchShowStructure).
   */
  @Transactional
  public Show create(CreateShowRequest request) {
    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "show";
    mediaItem.title = request.title();
    mediaItem.year = request.year();
    mediaItem.addedAt = Instant.now();
    mediaItem.rootFolder =
        rootFolderService.resolveOrDefault(request.rootFolderId(), "show").orElse(null);
    mediaItem.persist();

    Show show = new Show();
    show.mediaItem = mediaItem;
    show.overview = request.overview();
    show.posterPath = request.posterPath();
    show.backdropPath = request.backdropPath();
    show.qualityProfile = qualityProfileService.resolveOrThrow(request.qualityProfileId());

    if ("tmdb".equals(request.pluginSlug()) && request.externalId() != null) {
      TmdbShowStructure structure = tmdbMetadataProvider.fetchShowStructure(request.externalId());
      show.status = structure.status();
      show.persist();
      persistStructure(show, structure);
    } else {
      show.persist();
    }

    if (request.externalId() != null && request.pluginSlug() != null) {
      externalIdLinkService.link(mediaItem, request.pluginSlug(), request.externalId());
    }

    return show;
  }

  /**
   * Used by {@code JellyfinSyncService} and {@code SonarrSyncService} — same TMDB-driven
   * season/episode structure as {@link #create}, but from a server-reported title/year/root-folder
   * rather than a user-submitted request, and with no quality profile assigned (matches
   * server-synced movies, which are also unmonitored until the user assigns one).
   * Poster/backdrop/overview are a best-effort extra fetch (neither source's own reported ids carry
   * them) — unlike the season/episode tree, a failed lookup here doesn't block creation.
   */
  @Transactional
  public Show createFromExternalSource(
      String title, Integer year, String tmdbId, LibraryRootFolder rootFolder) {
    TmdbShowStructure structure = tmdbMetadataProvider.fetchShowStructure(tmdbId);

    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "show";
    mediaItem.title = title;
    mediaItem.year = year;
    mediaItem.addedAt = Instant.now();
    mediaItem.rootFolder = rootFolder;
    mediaItem.persist();

    Show show = new Show();
    show.mediaItem = mediaItem;
    show.status = structure.status();
    tmdbMetadataProvider
        .fetchShowById(tmdbId)
        .ifPresent(
            (MetadataSearchResult r) -> {
              show.overview = r.overview();
              show.posterPath = r.posterPath();
              show.backdropPath = r.backdropPath();
            });
    show.persist();
    persistStructure(show, structure);

    externalIdLinkService.link(mediaItem, "tmdb", tmdbId);
    return show;
  }

  @Transactional
  public Optional<Show> updateQualityProfile(UUID showId, UUID qualityProfileId) {
    return findById(showId)
        .map(
            show -> {
              show.qualityProfile = qualityProfileService.resolveOrThrow(qualityProfileId);
              return show;
            });
  }

  /**
   * {@code null} resets to "use the global show naming settings" — see {@link
   * Show#seasonFolderEnabled}.
   */
  @Transactional
  public Optional<Show> updateSeasonFolderEnabled(UUID showId, Boolean seasonFolderEnabled) {
    return findById(showId)
        .map(
            show -> {
              show.seasonFolderEnabled = seasonFolderEnabled;
              return show;
            });
  }

  /**
   * Cast/genres/similar for the detail page — see {@link TmdbMetadataProvider#fetchTvDetailExtras}.
   */
  public Optional<MediaDetailExtras> detailExtras(UUID showId) {
    return externalIdLinkService
        .findActiveExternalId(showId, "tmdb")
        .flatMap(tmdbMetadataProvider::fetchTvDetailExtras)
        .map(
            extras ->
                extras.withSimilar(
                    similarEnrichmentService.enrich(extras.similar(), "tmdb", "show")));
  }

  /**
   * Same idea as {@link #detailExtras}, but for a TMDB show Kosmos has no {@link Show} row for yet
   * — backs the detail screen a not-in-library card links to, so it opens something real instead of
   * falling back to a search.
   */
  public Optional<MediaPreview> preview(String externalId) {
    Optional<MetadataSearchResult> base = tmdbMetadataProvider.fetchShowById(externalId);
    if (base.isEmpty()) {
      return Optional.empty();
    }
    MetadataSearchResult b = base.get();
    MediaDetailExtras extras =
        tmdbMetadataProvider
            .fetchTvDetailExtras(externalId)
            .map(e -> e.withSimilar(similarEnrichmentService.enrich(e.similar(), "tmdb", "show")))
            .orElse(
                new MediaDetailExtras(
                    List.of(), List.of(), null, null, null, List.of(), List.of(), null, null));
    return Optional.of(
        new MediaPreview(
            externalId,
            "tmdb",
            "tv",
            b.title(),
            b.year(),
            b.overview(),
            b.posterPath(),
            b.backdropPath(),
            extras.genres(),
            extras.facts(),
            extras.voteAverage(),
            extras.voteCount(),
            extras.certification(),
            extras.cast(),
            extras.similar(),
            previewSeasons(externalId),
            List.of(),
            extras.trailerUrl()));
  }

  /**
   * Same season/episode tree {@link #createFromExternalSource} persists, reused read-only for the
   * not-owned preview screen so it can render the identical Seasons section an owned show's detail
   * page does. Unlike creation, a fetch failure here is best-effort — like the rest of {@link
   * #preview}, a missing/failed season tree just means an empty Seasons section, not a broken
   * preview.
   */
  private List<MediaPreview.PreviewSeason> previewSeasons(String tmdbId) {
    try {
      return tmdbMetadataProvider.fetchShowStructure(tmdbId).seasons().stream()
          .map(
              s ->
                  new MediaPreview.PreviewSeason(
                      s.seasonNumber(),
                      s.name(),
                      s.episodeCount(),
                      s.episodes().stream()
                          .map(
                              e ->
                                  new MediaPreview.PreviewEpisode(
                                      e.episodeNumber(), e.episodeNumber(), e.title(), e.airDate()))
                          .toList()))
          .toList();
    } catch (Exception e) {
      return List.of();
    }
  }

  private void persistStructure(Show show, TmdbShowStructure structure) {
    for (TmdbShowStructure.SeasonData seasonData : structure.seasons()) {
      Season season = new Season();
      season.show = show;
      season.seasonNumber = seasonData.seasonNumber();
      season.name = seasonData.name();
      season.overview = seasonData.overview();
      season.posterPath = seasonData.posterPath();
      season.episodeCount = seasonData.episodeCount();
      season.persist();

      for (TmdbShowStructure.EpisodeData episodeData : seasonData.episodes()) {
        MediaItem episodeMediaItem = new MediaItem();
        episodeMediaItem.contentType = "episode";
        episodeMediaItem.title = episodeData.title();
        episodeMediaItem.year = show.mediaItem.year;
        episodeMediaItem.addedAt = Instant.now();
        episodeMediaItem.rootFolder = show.mediaItem.rootFolder;
        episodeMediaItem.persist();

        Episode episode = new Episode();
        episode.mediaItem = episodeMediaItem;
        episode.season = season;
        episode.episodeNumber = episodeData.episodeNumber();
        episode.overview = episodeData.overview();
        episode.airDate = episodeData.airDate();
        episode.runtimeMinutes = episodeData.runtimeMinutes();
        episode.stillPath = episodeData.stillPath();
        episode.persist();
      }
    }
  }
}

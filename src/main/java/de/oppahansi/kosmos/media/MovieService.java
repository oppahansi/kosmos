package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.media.dto.CreateMovieRequest;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.SimilarEnrichmentService;
import de.oppahansi.kosmos.metadata.dto.MediaDetailExtras;
import de.oppahansi.kosmos.metadata.dto.MediaPreview;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.parsing.QualityProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class MovieService {

  @Inject QualityProfileService qualityProfileService;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject SimilarEnrichmentService similarEnrichmentService;

  public List<Movie> listAll() {
    return Movie.listAll();
  }

  public Optional<Movie> findById(UUID id) {
    return Movie.findByIdOptional(id);
  }

  @Transactional
  public Movie create(CreateMovieRequest request) {
    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "movie";
    mediaItem.title = request.title();
    mediaItem.year = request.year();
    mediaItem.addedAt = Instant.now();
    mediaItem.rootFolder =
        rootFolderService.resolveOrDefault(request.rootFolderId(), "movie").orElse(null);
    mediaItem.persist();

    Movie movie = new Movie();
    movie.mediaItem = mediaItem;
    movie.overview = request.overview();
    movie.posterPath = request.posterPath();
    movie.backdropPath = request.backdropPath();
    movie.qualityProfile = qualityProfileService.resolveOrThrow(request.qualityProfileId());

    if ("tmdb".equals(request.pluginSlug()) && request.externalId() != null) {
      tmdbMetadataProvider
          .fetchMovieDetails(request.externalId())
          .ifPresent(
              details -> {
                movie.runtimeMinutes =
                    details.runtime() != null && details.runtime() > 0 ? details.runtime() : null;
                movie.releaseDate = details.releaseDateAsLocalDate();
                movie.digitalReleaseDate = details.digitalReleaseDateUs();
              });
    }

    movie.persist();

    if (request.externalId() != null && request.pluginSlug() != null) {
      externalIdLinkService.link(mediaItem, request.pluginSlug(), request.externalId());
    }

    return movie;
  }

  @Transactional
  public Optional<Movie> updateQualityProfile(UUID movieId, UUID qualityProfileId) {
    return findById(movieId)
        .map(
            movie -> {
              movie.qualityProfile = qualityProfileService.resolveOrThrow(qualityProfileId);
              return movie;
            });
  }

  /**
   * Reassigns which registered root folder this movie belongs to. Does not itself move the file on
   * disk — same as picking a folder at creation time, this only changes where Kosmos considers the
   * title to belong.
   */
  @Transactional
  public Optional<Movie> updateRootFolder(UUID movieId, UUID rootFolderId) {
    return findById(movieId)
        .map(
            movie -> {
              movie.mediaItem.rootFolder = rootFolderService.resolveOrThrow(rootFolderId);
              return movie;
            });
  }

  @Transactional
  public Optional<Movie> updateMinimumAvailability(UUID movieId, MinimumAvailability availability) {
    return findById(movieId)
        .map(
            movie -> {
              movie.minimumAvailability = availability.name();
              return movie;
            });
  }

  /**
   * Cast/genres/similar for the detail page — see {@link
   * TmdbMetadataProvider#fetchMovieDetailExtras}.
   */
  public Optional<MediaDetailExtras> detailExtras(UUID movieId) {
    return externalIdLinkService
        .findActiveExternalId(movieId, "tmdb")
        .flatMap(tmdbMetadataProvider::fetchMovieDetailExtras)
        .map(
            extras ->
                extras.withSimilar(
                    similarEnrichmentService.enrich(extras.similar(), "tmdb", "movie")));
  }

  /**
   * Same idea as {@link #detailExtras}, but for a TMDB movie Kosmos has no {@link Movie} row for
   * yet — backs the detail screen a not-in-library card links to, so it opens something real
   * instead of falling back to a search.
   */
  public Optional<MediaPreview> preview(String externalId) {
    Optional<MetadataSearchResult> base = tmdbMetadataProvider.fetchMovieById(externalId);
    if (base.isEmpty()) {
      return Optional.empty();
    }
    MetadataSearchResult b = base.get();
    MediaDetailExtras extras =
        tmdbMetadataProvider
            .fetchMovieDetailExtras(externalId)
            .map(e -> e.withSimilar(similarEnrichmentService.enrich(e.similar(), "tmdb", "movie")))
            .orElse(
                new MediaDetailExtras(
                    List.of(), List.of(), null, null, null, List.of(), List.of(), null, null));
    return Optional.of(
        new MediaPreview(
            externalId,
            "tmdb",
            "movie",
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
            List.of(),
            List.of(),
            extras.trailerUrl()));
  }
}

package de.oppahansi.kosmos.metadata.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.metadata.MetadataProvider;
import de.oppahansi.kosmos.metadata.dto.MediaDetailExtras;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchItem;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.dto.TmdbCollectionResult;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * TMDB lookups for one already-known title — search, a single movie/show's own detail/extras, and
 * show-structure/season fetches. The browse/discover surface (trending, popular, upcoming, genre
 * lists) is a separate concern split out to {@link TmdbDiscoverClient} — this class had grown to
 * cover both and was the single largest file in the backend before the split.
 */
@ApplicationScoped
public class TmdbMetadataProvider implements MetadataProvider {

  private static final String SEARCH_URL = "https://api.themoviedb.org/3/search/movie";
  private static final String TV_SEARCH_URL = "https://api.themoviedb.org/3/search/tv";
  private static final String MOVIE_URL = "https://api.themoviedb.org/3/movie/";
  private static final String TV_URL = "https://api.themoviedb.org/3/tv/";
  private static final String AUTH_URL = "https://api.themoviedb.org/3/authentication";
  private static final String COLLECTION_URL = "https://api.themoviedb.org/3/collection/";
  private static final String FIND_URL = "https://api.themoviedb.org/3/find/";

  @ConfigProperty(name = "kosmos.metadata.tmdb.api-key")
  Optional<String> apiKey;

  @Inject TmdbTvDetailClient tmdbTvDetailClient;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String pluginSlug() {
    return "tmdb";
  }

  /** Whether the API key is set — a deploy-time env var, not something the UI can save. */
  public boolean isConfigured() {
    return apiKey.isPresent();
  }

  /**
   * Verifies the configured key actually works, via TMDB's own dedicated key-check endpoint ({@code
   * /authentication}, {"success":true} for a valid key) rather than inferring it from a real
   * search, which would conflate "key is bad" with "no results for this query".
   */
  public boolean testConnection() {
    if (apiKey.isEmpty()) {
      return false;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(AUTH_URL + "?api_key=" + apiKey.get()))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return response.statusCode() == 200
          && objectMapper.readTree(response.body()).path("success").asBoolean(false);
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

  /**
   * Search-as-you-type in SearchPage fires this on every debounced keystroke — with TV search
   * (below) now firing alongside it, this is the one real repeated-call hot path against TMDB (see
   * the research dossier's "HTTP response caching" entry). 24h keeps duplicate/near-duplicate
   * typing traffic from re-hitting TMDB within a session while staying well under TMDB's own
   * 6-month cache-retention ceiling; the bounded max-size (see application.properties) keeps
   * arbitrary user-typed queries from growing the cache without limit.
   */
  @Override
  @CacheResult(cacheName = "tmdb-search-movie")
  public List<MetadataSearchResult> search(String query) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(buildSearchUrl(query))).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parse(response.body());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("TMDB search failed", e);
    }
  }

  /**
   * Recovery path for {@code JellyfinSyncService#enrichFromTmdb} when Jellyfin's own reported TMDB
   * id turns out not to corroborate an independently-known year. TMDB's {@code year} query param
   * ranks a same-year match first but doesn't strictly filter to it — a same-titled movie from a
   * different year can still come back — so results are filtered to an exact year match here rather
   * than trusting the top result blindly.
   */
  @CacheResult(cacheName = "tmdb-movie-search-by-year")
  public Optional<MetadataSearchResult> searchMovieByTitleAndYear(String title, int year) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      String encodedQuery = URLEncoder.encode(title, StandardCharsets.UTF_8);
      String url =
          "%s?api_key=%s&query=%s&year=%d"
              .formatted(SEARCH_URL, apiKey.orElseThrow(), encodedQuery, year);
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parse(response.body()).stream()
          .filter(r -> r.year() != null && r.year() == year)
          .findFirst();
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * TMDB's search endpoint doesn't return runtime — only {@code /movie/{id}} does — so this is a
   * second call, made once at movie-creation time to populate {@link
   * de.oppahansi.kosmos.media.Movie#runtimeMinutes} for the size-gate quality check ({@code
   * parsing.QualityDefinition}). Best-effort: any failure (network, missing/unconfigured key, a
   * movie TMDB genuinely has no runtime for) returns empty rather than failing the whole movie
   * creation over an enrichment call.
   *
   * <p>Not a repeated-call hot path today (called exactly once per movie, at creation) — cached
   * anyway, cheaply, so a double "Add" click or a retry after a transient failure doesn't re-hit
   * TMDB for data that's essentially permanent once a movie has released. 7 days, not 24h like
   * {@link #search}: this is far more static than search results.
   */
  @CacheResult(cacheName = "tmdb-movie-details")
  public Optional<TmdbMovieDetails> fetchMovieDetails(String tmdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      MOVIE_URL
                          + tmdbId
                          + "?api_key="
                          + apiKey.orElseThrow()
                          + "&append_to_response=release_dates"))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      return Optional.of(objectMapper.readValue(response.body(), TmdbMovieDetails.class));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Backs {@code MovieCollectionService} — a collection's full member list, TMDB's own grouping.
   */
  @CacheResult(cacheName = "tmdb-collection")
  public Optional<TmdbCollectionResult> fetchCollection(String collectionId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(COLLECTION_URL + collectionId + "?api_key=" + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      TmdbCollectionResponse raw =
          objectMapper.readValue(response.body(), TmdbCollectionResponse.class);
      List<MetadataSearchResult> members =
          raw.parts() == null
              ? List.of()
              : raw.parts().stream().map(TmdbMappers::toSearchResult).toList();
      return Optional.of(
          new TmdbCollectionResult(
              String.valueOf(raw.id()), raw.name(), raw.posterPath(), raw.backdropPath(), members));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Full details for one known TMDB movie id — backs {@code JellyfinSyncService}'s poster/backdrop
   * backfill: Jellyfin's own ProviderIds give a TMDB id but no artwork, so this is the one real
   * TMDB round trip that fills that gap in. Same response shape as {@link #search} bar the extra
   * fields {@link TmdbMovie} ignores, just unwrapped (a single object, not a {@code results} list).
   */
  @CacheResult(cacheName = "tmdb-movie-by-id")
  public Optional<MetadataSearchResult> fetchMovieById(String tmdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(MOVIE_URL + tmdbId + "?api_key=" + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      return Optional.of(
          TmdbMappers.toSearchResult(objectMapper.readValue(response.body(), TmdbMovie.class)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Show-level counterpart to {@link #fetchMovieById} — backs {@code JellyfinSyncService}'s show
   * sync, which (like movies) only has a bare TMDB id from Jellyfin's ProviderIds and needs the
   * poster/backdrop/overview {@link #fetchShowStructure} doesn't carry.
   */
  @CacheResult(cacheName = "tmdb-show-by-id")
  public Optional<MetadataSearchResult> fetchShowById(String tmdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(TV_URL + tmdbId + "?api_key=" + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      return Optional.of(
          TmdbMappers.toSearchResult(objectMapper.readValue(response.body(), TmdbTvShow.class)));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Resolves a TheTVDB series id to its TMDB TV id, via TMDB's own {@code /find} endpoint — {@code
   * SonarrSyncService}'s fallback for the small fraction of series Sonarr doesn't already report a
   * native {@code tmdbId} for (Sonarr's series resource carries one directly for most titles; this
   * covers the rest without requiring a whole separate id-mapping dataset).
   */
  @CacheResult(cacheName = "tmdb-find-by-tvdb")
  public Optional<String> findTvIdByTvdbId(String tvdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      FIND_URL
                          + tvdbId
                          + "?api_key="
                          + apiKey.orElseThrow()
                          + "&external_source=tvdb_id"))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      JsonNode tvResults = objectMapper.readTree(response.body()).path("tv_results");
      return tvResults.isEmpty()
          ? Optional.empty()
          : Optional.of(tvResults.get(0).path("id").asText());
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /**
   * Whether TMDB tags this TV id with the literal {@code "anime"} keyword — distinct from the
   * {@code Animation} genre, which TMDB applies to Western cartoons just as much as anime (see
   * {@code JellyfinSyncService#classifyAnimeMatch}'s own doc for why genre alone isn't trustworthy:
   * e.g. Black Lagoon's genre list leads with "Action & Adventure", Animation only shows up further
   * down). Keywords are community-tagged and can be missing for obscure titles, so absence here
   * means "no signal either way", never "confirmed not anime" — callers only use a {@code true} to
   * rescue an otherwise-ambiguous match, never to reject one.
   */
  @CacheResult(cacheName = "tmdb-tv-anime-keyword")
  public boolean showHasAnimeKeyword(String tmdbId) {
    if (apiKey.isEmpty()) {
      return false;
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      TV_URL
                          + tmdbId
                          + "?api_key="
                          + apiKey.orElseThrow()
                          + "&append_to_response=keywords"))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return false;
      }
      JsonNode root = objectMapper.readTree(response.body());
      for (JsonNode kw : root.path("keywords").path("results")) {
        if ("anime".equalsIgnoreCase(kw.path("name").asText(""))) {
          return true;
        }
      }
      return false;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Cast, genres, certification, and recommendations for the movie detail page — one call via
   * {@code append_to_response} rather than four separate ones ({@code /credits}, {@code
   * /recommendations}, {@code /release_dates} each require their own request otherwise).
   */
  @CacheResult(cacheName = "tmdb-movie-detail-extras")
  public Optional<MediaDetailExtras> fetchMovieDetailExtras(String tmdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      MOVIE_URL
                          + tmdbId
                          + "?api_key="
                          + apiKey.orElseThrow()
                          + "&append_to_response=credits,recommendations,release_dates,videos"))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      TmdbMovieDetailFull d = objectMapper.readValue(response.body(), TmdbMovieDetailFull.class);
      return Optional.of(toMovieExtras(d));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  /** Show-detail counterpart to {@link #fetchMovieDetailExtras}. */
  @CacheResult(cacheName = "tmdb-tv-detail-extras")
  public Optional<MediaDetailExtras> fetchTvDetailExtras(String tmdbId) {
    if (apiKey.isEmpty()) {
      return Optional.empty();
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      TV_URL
                          + tmdbId
                          + "?api_key="
                          + apiKey.orElseThrow()
                          + "&append_to_response=credits,recommendations,content_ratings,videos"))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Optional.empty();
      }
      TmdbTvDetailFull d = objectMapper.readValue(response.body(), TmdbTvDetailFull.class);
      return Optional.of(toTvExtras(d));
    } catch (Exception e) {
      return Optional.empty();
    }
  }

  private MediaDetailExtras toMovieExtras(TmdbMovieDetailFull d) {
    List<String> genres =
        d.genres() == null ? List.of() : d.genres().stream().map(TmdbGenre::name).toList();
    String director =
        d.credits() != null && d.credits().crew() != null
            ? d.credits().crew().stream()
                .filter(c -> "Director".equals(c.job()))
                .map(TmdbCredits.Crew::name)
                .findFirst()
                .orElse(null)
            : null;
    String studio =
        d.productionCompanies() != null && !d.productionCompanies().isEmpty()
            ? d.productionCompanies().get(0).name()
            : null;
    String certification = usCertification(d.releaseDates());
    List<MediaDetailExtras.CastMember> cast = castFrom(d.credits());
    List<MetadataSearchItem> similar =
        d.recommendations() != null && d.recommendations().results() != null
            ? d.recommendations().results().stream()
                .map(TmdbMappers::toSearchResult)
                .map(MetadataSearchItem::unenriched)
                .toList()
            : List.of();

    List<MediaDetailExtras.Fact> facts = new ArrayList<>();
    if (director != null) facts.add(new MediaDetailExtras.Fact("Director", director));
    if (studio != null) facts.add(new MediaDetailExtras.Fact("Studio", studio));
    if (d.releaseDate() != null && !d.releaseDate().isBlank()) {
      facts.add(new MediaDetailExtras.Fact("Release", d.releaseDate()));
    }
    if (d.voteAverage() != null) {
      facts.add(
          new MediaDetailExtras.Fact(
              "TMDB",
              d.voteAverage() + (d.voteCount() != null ? " · " + d.voteCount() + " votes" : "")));
    }
    MediaDetailExtras.Collection collection =
        d.belongsToCollection() != null
            ? new MediaDetailExtras.Collection(
                String.valueOf(d.belongsToCollection().id()),
                d.belongsToCollection().name(),
                d.belongsToCollection().posterPath(),
                d.belongsToCollection().backdropPath())
            : null;
    return new MediaDetailExtras(
        genres,
        facts,
        d.voteAverage(),
        d.voteCount(),
        certification,
        cast,
        similar,
        trailerUrl(d.videos()),
        collection);
  }

  private MediaDetailExtras toTvExtras(TmdbTvDetailFull d) {
    List<String> genres =
        d.genres() == null ? List.of() : d.genres().stream().map(TmdbGenre::name).toList();
    String creator =
        d.createdBy() != null && !d.createdBy().isEmpty() ? d.createdBy().get(0).name() : null;
    String network =
        d.networks() != null && !d.networks().isEmpty() ? d.networks().get(0).name() : null;
    String certification = usContentRating(d.contentRatings());
    List<MediaDetailExtras.CastMember> cast = castFrom(d.credits());
    List<MetadataSearchItem> similar =
        d.recommendations() != null && d.recommendations().results() != null
            ? d.recommendations().results().stream()
                .map(TmdbMappers::toSearchResult)
                .map(MetadataSearchItem::unenriched)
                .toList()
            : List.of();

    List<MediaDetailExtras.Fact> facts = new ArrayList<>();
    if (creator != null) facts.add(new MediaDetailExtras.Fact("Creator", creator));
    if (network != null) facts.add(new MediaDetailExtras.Fact("Network", network));
    if (d.firstAirDate() != null && !d.firstAirDate().isBlank()) {
      facts.add(new MediaDetailExtras.Fact("First Aired", d.firstAirDate()));
    }
    if (d.voteAverage() != null) {
      facts.add(
          new MediaDetailExtras.Fact(
              "TMDB",
              d.voteAverage() + (d.voteCount() != null ? " · " + d.voteCount() + " votes" : "")));
    }
    return new MediaDetailExtras(
        genres,
        facts,
        d.voteAverage(),
        d.voteCount(),
        certification,
        cast,
        similar,
        trailerUrl(d.videos()),
        null);
  }

  /**
   * Picks the best YouTube trailer out of a title's {@code videos.results} — an official {@code
   * "Trailer"}-type entry if one exists, otherwise the first YouTube video of any type/officialness
   * (a teaser is still better than nothing). Non-YouTube hosts (Vimeo etc.) are skipped since the
   * frontend only knows how to open a YouTube watch URL.
   */
  private String trailerUrl(TmdbVideos videos) {
    if (videos == null || videos.results() == null) {
      return null;
    }
    List<TmdbVideos.Video> youtube =
        videos.results().stream().filter(v -> "YouTube".equals(v.site())).toList();
    return youtube.stream()
        .filter(v -> "Trailer".equals(v.type()) && Boolean.TRUE.equals(v.official()))
        .findFirst()
        .or(() -> youtube.stream().filter(v -> "Trailer".equals(v.type())).findFirst())
        .or(() -> youtube.stream().findFirst())
        .map(v -> "https://www.youtube.com/watch?v=" + v.key())
        .orElse(null);
  }

  private List<MediaDetailExtras.CastMember> castFrom(TmdbCredits credits) {
    if (credits == null || credits.cast() == null) {
      return List.of();
    }
    return credits.cast().stream()
        .limit(12)
        .map(c -> new MediaDetailExtras.CastMember(c.name(), c.character(), c.profilePath()))
        .toList();
  }

  private String usCertification(TmdbMovieDetailFull.ReleaseDates releaseDates) {
    if (releaseDates == null || releaseDates.results() == null) {
      return null;
    }
    return releaseDates.results().stream()
        .filter(r -> "US".equals(r.isoCode()))
        .findFirst()
        .flatMap(
            r ->
                r.releaseDates() == null
                    ? Optional.<String>empty()
                    : r.releaseDates().stream()
                        .map(TmdbMovieDetailFull.ReleaseDates.Certification::certification)
                        .filter(c -> c != null && !c.isBlank())
                        .findFirst())
        .orElse(null);
  }

  private String usContentRating(TmdbTvDetailFull.ContentRatings contentRatings) {
    if (contentRatings == null || contentRatings.results() == null) {
      return null;
    }
    return contentRatings.results().stream()
        .filter(r -> "US".equals(r.isoCode()) && r.rating() != null && !r.rating().isBlank())
        .map(TmdbTvDetailFull.ContentRatings.Country::rating)
        .findFirst()
        .orElse(null);
  }

  /**
   * TV-show search — same idea as {@link #search}, TMDB's shape just differs (name, not title).
   * Separate cache name from {@link #search} even though both are keyed by the same query string,
   * so a movie-only cache hit can't accidentally short-circuit the TV results for the same query.
   */
  @CacheResult(cacheName = "tmdb-search-tv")
  public List<MetadataSearchResult> searchTv(String query) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(
                  URI.create(
                      "%s?api_key=%s&query=%s"
                          .formatted(TV_SEARCH_URL, apiKey.orElseThrow(), encodedQuery)))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      TmdbTvSearchResponse parsed =
          objectMapper.readValue(response.body(), TmdbTvSearchResponse.class);
      return parsed.results().stream().map(TmdbMappers::toSearchResult).toList();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("TMDB TV search failed", e);
    }
  }

  /**
   * Fetches a show's full season/episode tree at show-creation time: one {@code /tv/{id}} call for
   * the season list, then one {@code /tv/{id}/season/{n}} call per season for its episodes. Unlike
   * {@link #fetchMovieDetails}, this isn't best-effort — a show with no structure isn't a useful
   * show to have added, so failures propagate rather than silently creating an empty shell.
   *
   * <p>The per-season/per-details work is delegated to {@link TmdbTvDetailClient}, a separate bean
   * specifically so each piece is independently cacheable — see that class's own doc comment for
   * why (the short version: {@code @CacheResult} can't intercept same-class private-method calls,
   * and per-season caching gives a real resume-after-partial-failure property this method's own
   * whole-tree cache below can't). Both layers are kept deliberately: this cache serves an
   * immediate duplicate call instantly without even touching the inner caches; the inner caches are
   * what make a *retry after failure* cheap.
   */
  @CacheResult(cacheName = "tmdb-show-structure")
  public TmdbShowStructure fetchShowStructure(String tmdbId) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    TmdbTvDetails details = tmdbTvDetailClient.fetchTvDetails(tmdbId);
    List<TmdbShowStructure.SeasonData> seasons = new ArrayList<>();
    for (TmdbTvDetails.Season season : details.seasons()) {
      List<TmdbShowStructure.EpisodeData> episodes =
          tmdbTvDetailClient.fetchSeasonEpisodes(tmdbId, season.seasonNumber());
      seasons.add(
          new TmdbShowStructure.SeasonData(
              season.seasonNumber(),
              season.name(),
              season.overview(),
              season.posterPath(),
              season.episodeCount(),
              episodes));
    }
    return new TmdbShowStructure(details.status(), seasons);
  }

  /**
   * Exposes {@link TmdbTvDetailClient#fetchSeasonEpisodes} beyond this package — {@code
   * media.AnimeService} uses it to enrich an anime's episode tree with TMDB's real per-episode data
   * once Fribb/anime-lists has resolved the anime to a TMDB TV id + season number. {@code
   * TmdbTvDetailClient} itself stays package-private (it's an internal caching split, not a public
   * API — see its own doc comment); this is a thin pass-through, not a duplicate of its
   * {@code @CacheResult}, which still applies since the call crosses a real bean boundary.
   */
  public List<TmdbShowStructure.EpisodeData> fetchSeasonEpisodes(String tmdbId, int seasonNumber) {
    return tmdbTvDetailClient.fetchSeasonEpisodes(tmdbId, seasonNumber);
  }

  String buildSearchUrl(String query) {
    String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
    return "%s?api_key=%s&query=%s".formatted(SEARCH_URL, apiKey.orElseThrow(), encodedQuery);
  }

  List<MetadataSearchResult> parse(String json) {
    try {
      TmdbSearchResponse response = objectMapper.readValue(json, TmdbSearchResponse.class);
      return response.results().stream().map(TmdbMappers::toSearchResult).toList();
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid TMDB response", e);
    }
  }
}

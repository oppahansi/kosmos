package de.oppahansi.kosmos.metadata.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.cache.PersistentCache;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * TMDB's browse/discover surface — trending, popular, upcoming, genre lists, and discover-by
 * genre/company/network — split out of {@link TmdbMetadataProvider} (see that class's own doc
 * comment) since these are all one cohesive "browse rows" concern with their own shared
 * fetch/filter/parse plumbing, distinct from {@link TmdbMetadataProvider}'s "one known title's
 * details" concern. Backs {@code DiscoverService} exclusively.
 */
@ApplicationScoped
public class TmdbDiscoverClient {

  private static final String TRENDING_MOVIE_URL = "https://api.themoviedb.org/3/trending/movie/";
  private static final String TRENDING_TV_URL = "https://api.themoviedb.org/3/trending/tv/";
  private static final String TRENDING_ALL_URL = "https://api.themoviedb.org/3/trending/all/";
  private static final String POPULAR_URL = "https://api.themoviedb.org/3/movie/popular";
  private static final String UPCOMING_MOVIES_URL = "https://api.themoviedb.org/3/movie/upcoming";
  private static final String POPULAR_TV_URL = "https://api.themoviedb.org/3/tv/popular";
  private static final String DISCOVER_MOVIE_URL = "https://api.themoviedb.org/3/discover/movie";
  private static final String DISCOVER_TV_URL = "https://api.themoviedb.org/3/discover/tv";
  private static final String MOVIE_GENRES_URL = "https://api.themoviedb.org/3/genre/movie/list";
  private static final String TV_GENRES_URL = "https://api.themoviedb.org/3/genre/tv/list";
  private static final String MOVIE_URL = "https://api.themoviedb.org/3/movie/";

  private static final int UPCOMING_TV_TARGET_COUNT = 20;
  private static final int UPCOMING_TV_MAX_PAGES = 5;

  @ConfigProperty(name = "kosmos.metadata.tmdb.api-key")
  Optional<String> apiKey;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Backs Discover/Home's "Trending" row/hero and the "Trending" list page's "Movies" filter —
   * TMDB's own {@code /trending/movie/{window}}, {@code window} being {@code day} or {@code week}.
   */
  @CacheResult(cacheName = "tmdb-trending")
  @PersistentCache
  public List<MetadataSearchResult> fetchTrendingMovies(
      String window, int page, String excludeLanguages) {
    return fetchMoviesFiltered(
        TRENDING_MOVIE_URL + window,
        "page=" + page,
        excludeLanguages,
        "TMDB trending fetch failed");
  }

  /**
   * Backs the "Trending" list page's "Series" filter — same idea as {@link #fetchTrendingMovies}.
   */
  @CacheResult(cacheName = "tmdb-trending-tv")
  @PersistentCache
  public List<MetadataSearchResult> fetchTrendingTv(
      String window, int page, String excludeLanguages) {
    return fetchTvFiltered(
        TRENDING_TV_URL + window,
        "page=" + page,
        excludeLanguages,
        "TMDB TV trending fetch failed");
  }

  /**
   * Backs Discover/Home's "Trending" row/hero (default) and the "Trending" list page's "All" filter
   * — TMDB's own {@code /trending/all/{window}}, mixing movies and series in one popularity-sorted
   * list with a {@code media_type} per result. Unlike every other list here, this can't be bound to
   * a single typed DTO (a movie result has {@code title}, a TV result has {@code name}, and the
   * list also contains person results this filters out entirely) — parsed by hand via {@link
   * #parseTrendingAll}.
   */
  @CacheResult(cacheName = "tmdb-trending-all")
  @PersistentCache
  public List<MetadataSearchResult> fetchTrendingAll(
      String window, int page, String excludeLanguages) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      String url = TRENDING_ALL_URL + window + "?api_key=" + apiKey.orElseThrow() + "&page=" + page;
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parseTrendingAll(response.body(), parseLanguages(excludeLanguages));
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("TMDB trending fetch failed", e);
    }
  }

  /**
   * Parses a comma-separated ISO 639-1 list (e.g. {@code "zh,hi"}) — blank/null means "exclude
   * nothing", so callers can filter with {@code !excluded.contains(lang)} unconditionally.
   */
  private Set<String> parseLanguages(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  private List<MetadataSearchResult> parseTrendingAll(String json, Set<String> excludeLanguages) {
    try {
      List<MetadataSearchResult> out = new ArrayList<>();
      for (JsonNode r : objectMapper.readTree(json).path("results")) {
        String mediaType = r.path("media_type").asText("");
        String language = r.path("original_language").asText("");
        if (excludeLanguages.contains(language)) {
          continue;
        }
        if ("movie".equals(mediaType)) {
          out.add(
              new MetadataSearchResult(
                  String.valueOf(r.path("id").asInt()),
                  textOrNull(r, "title"),
                  TmdbMappers.extractYear(textOrNull(r, "release_date")),
                  textOrNull(r, "overview"),
                  textOrNull(r, "poster_path"),
                  textOrNull(r, "backdrop_path"),
                  r.path("vote_average").isNumber() ? r.path("vote_average").asDouble() : null,
                  "movie",
                  null,
                  textOrNull(r, "release_date")));
        } else if ("tv".equals(mediaType)) {
          out.add(
              new MetadataSearchResult(
                  String.valueOf(r.path("id").asInt()),
                  textOrNull(r, "name"),
                  TmdbMappers.extractYear(textOrNull(r, "first_air_date")),
                  textOrNull(r, "overview"),
                  textOrNull(r, "poster_path"),
                  textOrNull(r, "backdrop_path"),
                  null,
                  "tv",
                  null,
                  null));
        }
        // "person" results and anything else are skipped — not a title Kosmos can show a card for.
      }
      return out;
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid TMDB response", e);
    }
  }

  private String textOrNull(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  /**
   * Backs Discover/Home's "Popular Movies" row (page 1, unfiltered) and the "Popular Movies" list
   * page's infinite scroll and language filter.
   */
  @CacheResult(cacheName = "tmdb-popular")
  @PersistentCache
  public List<MetadataSearchResult> fetchPopularMovies(int page, String excludeLanguages) {
    return fetchMoviesFiltered(
        POPULAR_URL, "page=" + page, excludeLanguages, "TMDB popular fetch failed");
  }

  /**
   * Backs Discover/Home's "Upcoming Movies" row (page 1, unfiltered) and its list page's infinite
   * scroll and language filter — TMDB's own {@code /movie/upcoming}.
   */
  @CacheResult(cacheName = "tmdb-upcoming-movies")
  @PersistentCache
  public List<MetadataSearchResult> fetchUpcomingMovies(int page, String excludeLanguages) {
    return fetchMoviesFiltered(
        UPCOMING_MOVIES_URL, "page=" + page, excludeLanguages, "TMDB upcoming movies fetch failed");
  }

  /**
   * Backs Discover/Home's "Popular Series" row (page 1, unfiltered) and its list page's infinite
   * scroll and language filter.
   */
  @CacheResult(cacheName = "tmdb-popular-tv")
  @PersistentCache
  public List<MetadataSearchResult> fetchPopularTv(int page, String excludeLanguages) {
    return fetchTvFiltered(
        POPULAR_TV_URL, "page=" + page, excludeLanguages, "TMDB popular TV fetch failed");
  }

  /**
   * Backs Discover/Home's "Upcoming Series" row. TMDB has no dedicated "upcoming TV" endpoint (only
   * movies get one) — this is {@code /discover/tv} filtered to a first-air-date in the future,
   * sorted soonest-first, which is the same query Overseerr/Jellyseerr use for the same row.
   *
   * <p>Unlike every other discover row, a large share of what this query surfaces — small/regional
   * productions ordered by "airs soonest" rather than any popularity signal — has no poster on TMDB
   * at all yet. Rather than showing a row half full of gray placeholder tiles, this pages through
   * results (capped at {@link #UPCOMING_TV_MAX_PAGES} TMDB calls) filtering to only posters-having
   * titles until it collects {@link #UPCOMING_TV_TARGET_COUNT} of them.
   */
  @CacheResult(cacheName = "tmdb-upcoming-tv")
  @PersistentCache
  public List<MetadataSearchResult> fetchUpcomingTv(String excludeLanguages) {
    String extraParams = "sort_by=first_air_date.asc&first_air_date.gte=" + LocalDate.now();
    List<MetadataSearchResult> collected = new ArrayList<>();
    for (int page = 1;
        page <= UPCOMING_TV_MAX_PAGES && collected.size() < UPCOMING_TV_TARGET_COUNT;
        page++) {
      List<MetadataSearchResult> pageResults =
          fetchTvFiltered(
              DISCOVER_TV_URL,
              extraParams + "&page=" + page,
              excludeLanguages,
              "TMDB upcoming TV fetch failed");
      for (MetadataSearchResult r : pageResults) {
        if (r.posterPath() != null) {
          collected.add(r);
          if (collected.size() >= UPCOMING_TV_TARGET_COUNT) {
            break;
          }
        }
      }
    }
    return collected;
  }

  /**
   * Backs the "Upcoming Series" list page's infinite scroll beyond page 1 (which reuses {@link
   * #fetchUpcomingTv(String)}'s curated, posters-only page instead) — a single raw {@code
   * /discover/tv} page, same query, no posters-only filtering. Unlike the home row, a list page the
   * user is actively scrolling through can show the occasional posterless placeholder same as every
   * other paginated list here; TMDB genuinely doesn't have art for everything this query surfaces.
   */
  @CacheResult(cacheName = "tmdb-upcoming-tv-paged")
  @PersistentCache
  public List<MetadataSearchResult> fetchUpcomingTv(int page, String excludeLanguages) {
    String extraParams =
        "sort_by=first_air_date.asc&first_air_date.gte=" + LocalDate.now() + "&page=" + page;
    return fetchTvFiltered(
        DISCOVER_TV_URL, extraParams, excludeLanguages, "TMDB upcoming TV fetch failed");
  }

  /** Backs Discover/Home's "Movie Genres" tile row — the fixed TMDB genre vocabulary for movies. */
  @CacheResult(cacheName = "tmdb-movie-genres")
  @PersistentCache
  public List<TmdbGenre> fetchMovieGenres() {
    return fetchGenres(MOVIE_GENRES_URL, "TMDB movie genre list fetch failed");
  }

  /** Backs Discover/Home's "Series Genres" tile row — the fixed TMDB genre vocabulary for TV. */
  @CacheResult(cacheName = "tmdb-tv-genres")
  @PersistentCache
  public List<TmdbGenre> fetchTvGenres() {
    return fetchGenres(TV_GENRES_URL, "TMDB TV genre list fetch failed");
  }

  private List<TmdbGenre> fetchGenres(String url, String failureMessage) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(url + "?api_key=" + apiKey.orElseThrow()))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return objectMapper.readValue(response.body(), TmdbGenreListResponse.class).genres();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }

  /**
   * Backs a genre tile's click-through page and its infinite scroll and language filter — movies
   * tagged with the given TMDB genre id.
   */
  @CacheResult(cacheName = "tmdb-discover-movie-genre")
  @PersistentCache
  public List<MetadataSearchResult> discoverMoviesByGenre(
      int genreId, int page, String excludeLanguages) {
    return fetchMoviesFiltered(
        DISCOVER_MOVIE_URL,
        "sort_by=popularity.desc&with_genres=" + genreId + "&page=" + page,
        excludeLanguages,
        "TMDB movie-by-genre discover failed");
  }

  /**
   * Backs a genre tile's click-through page and its infinite scroll and language filter — series
   * tagged with the given TMDB genre id.
   */
  @CacheResult(cacheName = "tmdb-discover-tv-genre")
  @PersistentCache
  public List<MetadataSearchResult> discoverTvByGenre(
      int genreId, int page, String excludeLanguages) {
    return fetchTvFiltered(
        DISCOVER_TV_URL,
        "sort_by=popularity.desc&with_genres=" + genreId + "&page=" + page,
        excludeLanguages,
        "TMDB TV-by-genre discover failed");
  }

  /**
   * Backs a studio tile's click-through page and its infinite scroll and language filter — movies
   * produced by the given TMDB company id.
   */
  @CacheResult(cacheName = "tmdb-discover-movie-company")
  @PersistentCache
  public List<MetadataSearchResult> discoverMoviesByCompany(
      int companyId, int page, String excludeLanguages) {
    return fetchMoviesFiltered(
        DISCOVER_MOVIE_URL,
        "sort_by=popularity.desc&with_companies=" + companyId + "&page=" + page,
        excludeLanguages,
        "TMDB movie-by-company discover failed");
  }

  /**
   * Backs a network tile's click-through page and its infinite scroll and language filter — series
   * airing on the given TMDB network id.
   */
  @CacheResult(cacheName = "tmdb-discover-tv-network")
  @PersistentCache
  public List<MetadataSearchResult> discoverTvByNetwork(
      int networkId, int page, String excludeLanguages) {
    return fetchTvFiltered(
        DISCOVER_TV_URL,
        "sort_by=popularity.desc&with_networks=" + networkId + "&page=" + page,
        excludeLanguages,
        "TMDB TV-by-network discover failed");
  }

  /**
   * Fetch + typed-parse + language-filter + map for every movie list in this class — pulled out
   * once all of trending/popular/upcoming/genre/company movie lists needed the same "read {@code
   * original_language} before it's discarded on the way to {@link MetadataSearchResult}" shape.
   */
  private List<MetadataSearchResult> fetchMoviesFiltered(
      String url, String extraParams, String excludeLanguages, String failureMessage) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      String fullUrl =
          url
              + (url.contains("?") ? "&" : "?")
              + "api_key="
              + apiKey.orElseThrow()
              + (extraParams == null ? "" : "&" + extraParams);
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      TmdbSearchResponse parsed = objectMapper.readValue(response.body(), TmdbSearchResponse.class);
      Set<String> excluded = parseLanguages(excludeLanguages);
      return parsed.results().stream()
          .filter(m -> !excluded.contains(m.originalLanguage()))
          .map(TmdbMappers::toSearchResult)
          .toList();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }

  /** TV counterpart of {@link #fetchMoviesFiltered}. */
  private List<MetadataSearchResult> fetchTvFiltered(
      String url, String extraParams, String excludeLanguages, String failureMessage) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      String fullUrl =
          url
              + (url.contains("?") ? "&" : "?")
              + "api_key="
              + apiKey.orElseThrow()
              + (extraParams == null ? "" : "&" + extraParams);
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      TmdbTvSearchResponse parsed =
          objectMapper.readValue(response.body(), TmdbTvSearchResponse.class);
      Set<String> excluded = parseLanguages(excludeLanguages);
      return parsed.results().stream()
          .filter(s -> !excluded.contains(s.originalLanguage()))
          .map(TmdbMappers::toSearchResult)
          .toList();
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }

  /**
   * Backs Discover/Home's "Because You Added" row — TMDB's own {@code /movie/{id}/recommendations},
   * same response shape as {@link #fetchTrendingMovies}/{@link #fetchPopularMovies}. Cached per
   * seed movie id rather than globally, since unlike trending/popular this genuinely varies by
   * which movie in the library it's computed from.
   */
  @CacheResult(cacheName = "tmdb-movie-recommendations")
  @PersistentCache
  public List<MetadataSearchResult> fetchMovieRecommendations(String tmdbId) {
    return fetchList(MOVIE_URL + tmdbId + "/recommendations", "TMDB recommendations fetch failed");
  }

  private List<MetadataSearchResult> fetchList(String url, String failureMessage) {
    return fetch(url, null, failureMessage, this::parse);
  }

  private List<MetadataSearchResult> fetch(
      String url,
      String extraParams,
      String failureMessage,
      Function<String, List<MetadataSearchResult>> parser) {
    if (apiKey.isEmpty()) {
      throw new IllegalStateException("kosmos.metadata.tmdb.api-key is not configured");
    }
    try {
      String separator = url.contains("?") ? "&" : "?";
      String fullUrl =
          url
              + separator
              + "api_key="
              + apiKey.orElseThrow()
              + (extraParams == null ? "" : "&" + extraParams);
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(fullUrl)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      return parser.apply(response.body());
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }

  private List<MetadataSearchResult> parse(String json) {
    try {
      TmdbSearchResponse response = objectMapper.readValue(json, TmdbSearchResponse.class);
      return response.results().stream().map(TmdbMappers::toSearchResult).toList();
    } catch (IOException e) {
      throw new IllegalArgumentException("Invalid TMDB response", e);
    }
  }
}

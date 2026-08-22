package de.oppahansi.kosmos.metadata.anilist;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.cache.PersistentCache;
import de.oppahansi.kosmos.metadata.MetadataProvider;
import de.oppahansi.kosmos.metadata.dto.MediaDetailExtras;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchItem;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Anime metadata via AniList's public GraphQL API — no API key needed. AniList's {@code Media}
 * search/lookup is public data with no registration or client-identification requirement, unlike
 * AniDB's hash-based file lookup — see {@code AniDbUdpClient}'s own doc comment for that.
 */
@ApplicationScoped
public class AniListMetadataProvider implements MetadataProvider {

  private static final String GRAPHQL_URL = "https://graphql.anilist.co";
  private static final String HTML_TAG = "<[^>]+>";

  /**
   * AniList's actual current limit is 30 requests/minute (confirmed via its own {@code
   * X-RateLimit-Limit} response header — down from the historical 90/minute after past abuse); 25
   * leaves headroom rather than pacing right up against the edge. A single anime lookup never gets
   * close to this, but a Jellyfin library sync can hit {@link #fetchById} for hundreds of distinct
   * ids in one run — without pacing, most of those beyond the first ~30 come back 429, which
   * previously got silently misread as "not found" (see {@link #post}).
   */
  private static final int MAX_REQUESTS_PER_WINDOW = 25;

  private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(60);

  private final Deque<Instant> recentRequests = new ArrayDeque<>();

  private static final String SEARCH_QUERY =
      """
      query ($search: String) {
        Page(page: 1, perPage: 20) {
          media(search: $search, type: ANIME) {
            id
            title { romaji english }
            startDate { year }
            description(asHtml: false)
            coverImage { large }
            bannerImage
            status
            episodes
          }
        }
      }
      """;

  private static final String BY_ID_QUERY =
      """
      query ($id: Int) {
        Media(id: $id, type: ANIME) {
          id
          title { romaji english }
          startDate { year }
          description(asHtml: false)
          coverImage { large }
          status
          episodes
        }
      }
      """;

  /**
   * Same fields as {@link #BY_ID_QUERY}, batched via AniList's {@code id_in} filter — see {@link
   * #fetchByIds}.
   */
  private static final String BY_IDS_QUERY =
      """
      query ($ids: [Int]) {
        Page(page: 1, perPage: 50) {
          media(id_in: $ids, type: ANIME) {
            id
            title { romaji english }
            startDate { year }
            description(asHtml: false)
            coverImage { large }
            status
            episodes
          }
        }
      }
      """;

  /** Ids per {@link #fetchByIds} request — comfortably under AniList's page-size cap of 50. */
  private static final int BATCH_SIZE = 25;

  private static final String DETAIL_EXTRAS_QUERY =
      """
      query ($id: Int) {
        Media(id: $id, type: ANIME) {
          id
          title { romaji english }
          startDate { year }
          description(asHtml: false)
          coverImage { large }
          status
          episodes
          genres
          averageScore
          trailer { id site }
          studios(isMain: true) { nodes { name } }
          recommendations(sort: RATING_DESC, perPage: 10) {
            nodes {
              mediaRecommendation {
                id
                title { romaji english }
                startDate { year }
                description(asHtml: false)
                coverImage { large }
              }
            }
          }
        }
      }
      """;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Override
  public String pluginSlug() {
    return "anilist";
  }

  /**
   * Backs the frontend's Anime search tab, previously always empty (TMDB has no anime media type of
   * its own). 24h cache, same reasoning and TTL as TMDB's own search-as-you-type caching.
   */
  @Override
  @CacheResult(cacheName = "anilist-search")
  @PersistentCache
  public List<MetadataSearchResult> search(String query) {
    AniListSearchResponse response =
        post(SEARCH_QUERY, Map.of("search", query), AniListSearchResponse.class);
    List<AniListMedia> media =
        response.data() != null && response.data().Page() != null
            ? response.data().Page().media()
            : List.of();
    return media.stream().map(this::toSearchResult).toList();
  }

  /**
   * Enough detail for {@code AnimeService} to create an {@code Anime} row — title, overview,
   * poster, status, and total episode count. AniList's own per-episode data is too
   * sparse/unreliable to build a real {@code AnimeEpisode} tree from directly — Fribb/TheXEM close
   * that gap by cross-referencing to a TVDB entry that actually has one.
   */
  @CacheResult(cacheName = "anilist-media")
  @PersistentCache
  public Optional<AniListAnimeDetails> fetchById(String externalId) {
    AniListMediaResponse response =
        post(BY_ID_QUERY, Map.of("id", Integer.valueOf(externalId)), AniListMediaResponse.class);
    AniListMedia media = response.data() != null ? response.data().Media() : null;
    if (media == null) {
      return Optional.empty();
    }
    return Optional.of(
        new AniListAnimeDetails(
            title(media),
            stripHtml(media.description()),
            poster(media),
            media.status(),
            media.episodes(),
            media.startDate() != null ? media.startDate().year() : null));
  }

  /**
   * Batched form of {@link #fetchById} for a Jellyfin library sync, where a single run can have
   * hundreds of distinct Fribb-matched AniList ids to resolve — one request per {@link #BATCH_SIZE}
   * ids via {@code id_in} instead of one request per id, so a sync's AniList cost is a handful of
   * requests rather than hundreds racing {@link #MAX_REQUESTS_PER_WINDOW}. Not cached like {@link
   * #fetchById} — a sync calls this once per distinct id already, so there's nothing to dedupe, and
   * caching a batch response under its whole id list would never hit again anyway. Ids AniList has
   * no match for are simply absent from the returned map.
   */
  public Map<Integer, AniListAnimeDetails> fetchByIds(List<Integer> ids) {
    Map<Integer, AniListAnimeDetails> results = new LinkedHashMap<>();
    for (int start = 0; start < ids.size(); start += BATCH_SIZE) {
      List<Integer> chunk = ids.subList(start, Math.min(start + BATCH_SIZE, ids.size()));
      AniListSearchResponse response =
          post(BY_IDS_QUERY, Map.of("ids", chunk), AniListSearchResponse.class);
      List<AniListMedia> media =
          response.data() != null && response.data().Page() != null
              ? response.data().Page().media()
              : List.of();
      for (AniListMedia m : media) {
        results.put(
            m.id(),
            new AniListAnimeDetails(
                title(m),
                stripHtml(m.description()),
                poster(m),
                m.status(),
                m.episodes(),
                m.startDate() != null ? m.startDate().year() : null));
      }
    }
    return results;
  }

  /**
   * Genres, studio, average score, and recommendations for the anime detail page — AniList has no
   * per-title cast/voice-actor query cheap enough to bother with here, so {@code
   * MediaDetailExtras.cast()} is always empty for anime.
   */
  @CacheResult(cacheName = "anilist-detail-extras")
  @PersistentCache
  public Optional<MediaDetailExtras> fetchDetailExtras(String externalId) {
    AniListMediaResponse response =
        post(
            DETAIL_EXTRAS_QUERY,
            Map.of("id", Integer.valueOf(externalId)),
            AniListMediaResponse.class);
    AniListMedia media = response.data() != null ? response.data().Media() : null;
    if (media == null) {
      return Optional.empty();
    }

    List<String> genres = media.genres() != null ? media.genres() : List.of();
    String studio =
        media.studios() != null
                && media.studios().nodes() != null
                && !media.studios().nodes().isEmpty()
            ? media.studios().nodes().get(0).name()
            : null;
    List<MetadataSearchItem> similar =
        media.recommendations() != null && media.recommendations().nodes() != null
            ? media.recommendations().nodes().stream()
                .map(AniListMedia.Recommendations.Node::mediaRecommendation)
                .filter(m -> m != null)
                .map(this::toSearchResult)
                .map(MetadataSearchItem::unenriched)
                .toList()
            : List.of();

    List<MediaDetailExtras.Fact> facts = new ArrayList<>();
    if (studio != null) facts.add(new MediaDetailExtras.Fact("Studio", studio));
    if (media.startDate() != null && media.startDate().year() != null) {
      facts.add(
          new MediaDetailExtras.Fact("First Aired", String.valueOf(media.startDate().year())));
    }
    Double averageScore = media.averageScore() != null ? media.averageScore() / 10.0 : null;
    if (averageScore != null) {
      facts.add(new MediaDetailExtras.Fact("AniList", averageScore + " / 10"));
    }
    String trailerUrl =
        media.trailer() != null && "youtube".equals(media.trailer().site())
            ? "https://www.youtube.com/watch?v=" + media.trailer().id()
            : null;

    return Optional.of(
        new MediaDetailExtras(
            genres, facts, averageScore, null, null, List.of(), similar, trailerUrl, null));
  }

  private MetadataSearchResult toSearchResult(AniListMedia media) {
    return new MetadataSearchResult(
        String.valueOf(media.id()),
        title(media),
        media.startDate() != null ? media.startDate().year() : null,
        stripHtml(media.description()),
        poster(media),
        media.bannerImage(),
        null,
        "anime",
        media.episodes(),
        null);
  }

  private String title(AniListMedia media) {
    if (media.title() == null) {
      return "Untitled";
    }
    return media.title().english() != null ? media.title().english() : media.title().romaji();
  }

  private String poster(AniListMedia media) {
    return media.coverImage() != null ? media.coverImage().large() : null;
  }

  private String stripHtml(String description) {
    return description == null ? null : description.replaceAll(HTML_TAG, "").trim();
  }

  private <T> T post(String query, Map<String, Object> variables, Class<T> responseType) {
    awaitRateLimit();
    try {
      String body = objectMapper.writeValueAsString(new GraphQlRequest(query, variables));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(GRAPHQL_URL))
              .header("Content-Type", "application/json")
              .header("Accept", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        // An error response body has no `data` field, which would otherwise deserialize as
        // data() == null — indistinguishable from every caller's own "not found" case.
        throw new IllegalStateException("AniList returned " + response.statusCode());
      }
      return objectMapper.readValue(response.body(), responseType);
    } catch (IOException | InterruptedException e) {
      throw new IllegalStateException("AniList request failed", e);
    }
  }

  /**
   * Blocks the calling thread as needed to stay under {@link #MAX_REQUESTS_PER_WINDOW} — a bulk
   * sync runs on a background job thread anyway, so pacing it here is free; the alternative is
   * requests failing with 429 further down and getting misread as failures worth retrying rather
   * than an intentional slowdown.
   */
  private synchronized void awaitRateLimit() {
    Instant now = Instant.now();
    dropExpired(now);
    if (recentRequests.size() >= MAX_REQUESTS_PER_WINDOW) {
      Instant oldest = recentRequests.peekFirst();
      long waitMs = RATE_LIMIT_WINDOW.minus(Duration.between(oldest, now)).toMillis() + 50;
      if (waitMs > 0) {
        try {
          Thread.sleep(waitMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while pacing AniList requests", e);
        }
      }
      dropExpired(Instant.now());
    }
    recentRequests.addLast(Instant.now());
  }

  private void dropExpired(Instant now) {
    while (!recentRequests.isEmpty()
        && Duration.between(recentRequests.peekFirst(), now).compareTo(RATE_LIMIT_WINDOW) > 0) {
      recentRequests.pollFirst();
    }
  }

  private record GraphQlRequest(String query, Map<String, Object> variables) {}
}

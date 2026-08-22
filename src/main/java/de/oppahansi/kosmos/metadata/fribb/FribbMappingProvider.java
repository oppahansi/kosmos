package de.oppahansi.kosmos.metadata.fribb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.cache.PersistentCache;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fribb/anime-lists' anime-id cross-reference table — a single static ~7.5MB JSON file on GitHub,
 * no auth or registration needed (unlike AniDB), actively maintained as the successor to the now-
 * archived manami-project/anime-offline-database. Loaded and cached as a single blob keyed by
 * AniList id, the same "whole thing, long TTL" shape already used for {@code tmdb-trending}/{@code
 * tmdb-popular} — this file changes on the order of days/weeks, not per request, and re-fetching
 * 7.5MB on every anime add would be wasteful.
 *
 * <p>Best-effort: this is an enrichment source, not a required one — {@code media.AnimeService}
 * already has a perfectly usable flat-episode fallback from AniList alone, so a fetch failure here
 * returns an empty map rather than blocking anime creation.
 */
@ApplicationScoped
public class FribbMappingProvider {

  private static final String MAPPING_URL =
      "https://raw.githubusercontent.com/Fribb/anime-lists/master/anime-list-full.json";

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  @CacheResult(cacheName = "fribb-mapping")
  @PersistentCache
  public Map<Integer, FribbEntry> loadMapping() {
    try {
      HttpRequest request = HttpRequest.newBuilder().uri(URI.create(MAPPING_URL)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return Map.of();
      }
      List<FribbEntry> entries =
          objectMapper.readValue(response.body(), new TypeReference<List<FribbEntry>>() {});
      return entries.stream()
          .filter(e -> e.anilistId() != null)
          .collect(Collectors.toMap(FribbEntry::anilistId, e -> e, (a, b) -> a));
    } catch (IOException | InterruptedException e) {
      return Map.of();
    }
  }

  /**
   * The same table, reversed to TMDB TV id — Kosmos's only reliable way to tell a Jellyfin
   * "tvshows" item is actually anime: Jellyfin's own {@code CollectionType} doesn't distinguish
   * them (both are just "tvshows"), but this table is curated by the anime-tracking community
   * specifically to link each AniList entry to its TMDB id, so a hit here is a real
   * cross-reference, not a guess. Cached independently of {@link #loadMapping} (same TTL/shape)
   * since it's rebuilt from that map's values rather than re-fetched — a cache hit on one likely
   * means a cache hit on the other, but each still needs its own entry to avoid rebuilding the
   * reverse index on every call once {@link #loadMapping} is warm.
   */
  @CacheResult(cacheName = "fribb-by-tmdb-tv")
  @PersistentCache
  public Map<Integer, FribbEntry> loadMappingByTmdbTvId() {
    Map<Integer, FribbEntry> byTmdbTvId = new LinkedHashMap<>();
    for (FribbEntry entry : loadMapping().values()) {
      Integer tmdbTvId = entry.themoviedbId() != null ? entry.themoviedbId().tv() : null;
      if (tmdbTvId != null) {
        byTmdbTvId.putIfAbsent(tmdbTvId, entry);
      }
    }
    return byTmdbTvId;
  }

  /**
   * Every AniList cour sharing {@code tmdbTvId} — {@code media.AnimeService}'s season chain for one
   * anime franchise, ordered by {@code season.tmdb} (nulls last, since a cour with no reported TMDB
   * season number can't be placed in the sequence), then by AniList id as a stable tiebreak for
   * multiple cours sharing one TMDB season (e.g. a "Part 1"/"Part 2" split TMDB itself doesn't
   * distinguish) — AniList ids are assigned roughly in the order titles were added, which tracks
   * release order closely enough to place parts correctly without an extra fetch just to sort by.
   * Unlike {@link #loadMappingByTmdbTvId}, which only keeps the first hit per TMDB id for
   * classification, this keeps every one.
   */
  public List<FribbEntry> seasonsForTmdbTvId(int tmdbTvId) {
    return loadMapping().values().stream()
        .filter(
            e ->
                e.themoviedbId() != null
                    && e.themoviedbId().tv() != null
                    && e.themoviedbId().tv() == tmdbTvId)
        .sorted(
            Comparator.<FribbEntry, Integer>comparing(
                    e -> e.season() != null ? e.season().tmdb() : null,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(FribbEntry::anilistId))
        .toList();
  }
}

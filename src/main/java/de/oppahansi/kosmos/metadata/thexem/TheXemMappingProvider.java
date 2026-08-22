package de.oppahansi.kosmos.metadata.thexem;

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
import java.util.List;
import java.util.Optional;

/**
 * TheXEM's scene/AniDB absolute-episode-numbering map: a long-running show's continuous "absolute"
 * numbering, which is what fansub filenames actually carry, doesn't always line up 1:1 with a
 * per-cour AniDB entry's own episode count. Verified live against a real divergent case (anidb id
 * 144, {@code GET https://thexem.info/map/all?origin=anidb&id=144}): fansub-visible absolute
 * episode 168 corresponds to AniDB's own season 2 episode 1. No auth or client registration needed,
 * unlike AniDB itself — see {@code metadata.anidb} for that one.
 *
 * <p>Best-effort, same reasoning as {@link
 * de.oppahansi.kosmos.metadata.fribb.FribbMappingProvider}: an enrichment source, not a required
 * one. {@code media.AnimeService} already has a working "absolute == within-cour episode number"
 * fallback; a missing map, a missing episode within it, or a fetch failure all just mean that
 * fallback stays in effect for that one episode.
 */
@ApplicationScoped
public class TheXemMappingProvider {

  private static final String MAP_URL = "https://thexem.info/map/all?origin=anidb&id=";

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * Resolves the real fansub-visible absolute episode number for one AniDB episode. A map with more
   * than one internal "season" bucket for the same episode number — a real but uncommon shape,
   * confirmed live on id 144/135/145; most anidb ids map flatly under season 1 — is disambiguated
   * by preferring the lowest season number, since AniDB's own episode numbering has no season
   * concept and Kosmos's per-cour {@link de.oppahansi.kosmos.media.Anime} entity always corresponds
   * to a single such id.
   */
  public Optional<Integer> sceneAbsoluteForAnidbEpisode(int anidbId, int anidbEpisodeNumber) {
    return loadMap(anidbId).stream()
        .filter(
            e ->
                e.anidb() != null
                    && e.anidb().episode() != null
                    && e.anidb().episode() == anidbEpisodeNumber
                    && e.scene() != null
                    && e.scene().absolute() != null)
        .min(
            Comparator.comparingInt(
                e -> e.anidb().season() != null ? e.anidb().season() : Integer.MAX_VALUE))
        .map(e -> e.scene().absolute());
  }

  @CacheResult(cacheName = "thexem-anidb-map")
  @PersistentCache
  List<TheXemEntry> loadMap(int anidbId) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create(MAP_URL + anidbId)).GET().build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        return List.of();
      }
      TheXemAllMapResponse parsed =
          objectMapper.readValue(response.body(), TheXemAllMapResponse.class);
      if (!"success".equals(parsed.result()) || parsed.data() == null) {
        return List.of();
      }
      return parsed.data();
    } catch (IOException | InterruptedException e) {
      return List.of();
    }
  }
}

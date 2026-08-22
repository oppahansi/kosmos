package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.downloads.dto.GrabRequest;
import de.oppahansi.kosmos.indexers.Indexer;
import de.oppahansi.kosmos.indexers.TorznabClient;
import de.oppahansi.kosmos.indexers.dto.TorznabResult;
import de.oppahansi.kosmos.media.Anime;
import de.oppahansi.kosmos.media.AnimeEpisode;
import de.oppahansi.kosmos.parsing.AnimeReleaseParser;
import de.oppahansi.kosmos.parsing.QualityDefinitionService;
import de.oppahansi.kosmos.parsing.ScoringEngine;
import de.oppahansi.kosmos.parsing.dto.ParsedAnimeRelease;
import de.oppahansi.kosmos.parsing.dto.ParsedRelease;
import de.oppahansi.kosmos.parsing.dto.ScoredRelease;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * The anime counterpart to {@link TvAutomaticSearchJob} — same unattended search-and-grab shape and
 * the same episode-at-a-time correctness gate, but matching against {@link
 * AnimeEpisode#absoluteEpisodeNumber} (what fansub filenames actually use) via {@link
 * AnimeReleaseParser} instead of season/episode via {@link
 * de.oppahansi.kosmos.parsing.ReleaseParser}. Batch releases are excluded from automatic grabs for
 * the same reason TV excludes season packs — episode-at-a-time is simpler to get right for a first
 * pass; matching into a batch's episode range is a real gap left for later, same as TV's.
 *
 * <p>{@link ParsedAnimeRelease} is converted to a {@link ParsedRelease} for scoring purposes only —
 * {@link ScoringEngine} and {@link QualityDefinitionService} operate on the scene shape, and every
 * field a custom format or size-gate rule actually reads (title, resolution, source, codecs) has a
 * direct anime equivalent; {@code seasonNumber} has no anime meaning and is left null, matching how
 * a movie release (no season either) already scores through the same engine.
 */
@ApplicationScoped
public class AnimeAutomaticSearchJob implements JobHandler {

  @Inject TorznabClient torznabClient;
  @Inject AnimeReleaseParser animeReleaseParser;
  @Inject ScoringEngine scoringEngine;
  @Inject QualityDefinitionService qualityDefinitionService;
  @Inject GrabService grabService;
  @Inject BlocklistService blocklistService;

  @Override
  public String jobName() {
    return "anime-automatic-search";
  }

  @Override
  public String displayName() {
    return "Anime Automatic Search";
  }

  @Override
  public int defaultIntervalSeconds() {
    return 21600; // 6 hours, same cadence as the movie/TV jobs
  }

  @Override
  public String run(ProgressReporter progress) {
    List<UUID> eligible = QuarkusTransaction.requiringNew().call(this::findEligibleEpisodeIds);
    for (UUID episodeId : eligible) {
      try {
        QuarkusTransaction.requiringNew().run(() -> searchAndGrab(episodeId));
      } catch (RuntimeException e) {
        // Left ungrabbed; retried on the next tick, same as the movie/TV jobs.
      }
    }
    return null;
  }

  private List<UUID> findEligibleEpisodeIds() {
    return AnimeEpisode.<AnimeEpisode>list(
            "season.anime.qualityProfile is not null and monitored = true")
        .stream()
        .map(episode -> episode.mediaItemId)
        .filter(mediaItemId -> !Grab.hasActiveGrab(mediaItemId))
        .toList();
  }

  private void searchAndGrab(UUID episodeMediaItemId) {
    AnimeEpisode episode = AnimeEpisode.<AnimeEpisode>findById(episodeMediaItemId);
    if (episode == null
        || episode.season.anime.qualityProfile == null
        || !episode.monitored
        || episode.absoluteEpisodeNumber == null) {
      return;
    }
    Anime anime = episode.season.anime;
    List<Indexer> indexers = Indexer.list("enabled", true);
    if (indexers.isEmpty()) {
      return;
    }
    DownloadClient client = DownloadClient.<DownloadClient>find("enabled", true).firstResult();
    if (client == null) {
      return;
    }

    String query = "%s - %02d".formatted(anime.mediaItem.title, episode.absoluteEpisodeNumber);

    Best best = null;
    for (Indexer indexer : indexers) {
      List<TorznabResult> results;
      try {
        results = torznabClient.search(indexer.baseUrl, indexer.apiKey, query);
      } catch (IOException | InterruptedException e) {
        continue;
      }
      for (TorznabResult raw : results) {
        if (blocklistService.isBlocked(episodeMediaItemId, raw.downloadUrl())) {
          continue;
        }
        ParsedAnimeRelease parsed = animeReleaseParser.parse(raw.title());
        if (!matchesEpisode(parsed, episode)) {
          continue;
        }
        ParsedRelease scoringShape = toScoringShape(parsed);
        ScoredRelease scored = scoringEngine.score(scoringShape, anime.qualityProfile);
        boolean sizeOk =
            qualityDefinitionService.checkSizeGate(
                    scoringShape, raw.sizeBytes(), episode.runtimeMinutes)
                == null;
        if (scored.passesCutoff()
            && sizeOk
            && (best == null || scored.totalScore() > best.scored.totalScore())) {
          best = new Best(raw, scoringShape, scored);
        }
      }
    }
    if (best == null) {
      return;
    }

    grabService.grab(
        episodeMediaItemId,
        new GrabRequest(
            best.raw.title(),
            best.raw.downloadUrl(),
            best.scoringShape.resolution(),
            best.scoringShape.source(),
            best.scoringShape.videoCodec(),
            best.scored.totalScore(),
            client.id));
  }

  private record Best(TorznabResult raw, ParsedRelease scoringShape, ScoredRelease scored) {}

  /**
   * The correctness gate the class doc describes — a batch release or one with no episode marker at
   * all never matches, and the marker has to equal the specific episode being searched for.
   */
  private boolean matchesEpisode(ParsedAnimeRelease parsed, AnimeEpisode episode) {
    return !parsed.batch()
        && parsed.episodeNumber() != null
        && parsed.episodeNumber().equals(episode.absoluteEpisodeNumber);
  }

  private ParsedRelease toScoringShape(ParsedAnimeRelease parsed) {
    return new ParsedRelease(
        parsed.title(),
        parsed.showTitle(),
        null,
        parsed.resolution(),
        parsed.source(),
        parsed.videoCodec(),
        parsed.audioCodec(),
        null,
        parsed.releaseGroup(),
        false,
        false,
        false,
        null,
        parsed.episodeNumber());
  }
}

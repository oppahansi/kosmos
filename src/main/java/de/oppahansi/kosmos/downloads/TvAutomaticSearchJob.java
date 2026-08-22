package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.downloads.dto.GrabRequest;
import de.oppahansi.kosmos.indexers.Indexer;
import de.oppahansi.kosmos.indexers.TorznabClient;
import de.oppahansi.kosmos.indexers.dto.TorznabResult;
import de.oppahansi.kosmos.media.Episode;
import de.oppahansi.kosmos.media.Show;
import de.oppahansi.kosmos.parsing.QualityDefinitionService;
import de.oppahansi.kosmos.parsing.ReleaseParser;
import de.oppahansi.kosmos.parsing.ScoringEngine;
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
 * The TV counterpart to {@link AutomaticSearchJob} — same unattended search-and-grab shape, one
 * real difference: a movie release only has to match the movie, but a TV release has to match the
 * *specific episode*, so every candidate is parsed and its {@code seasonNumber}/{@code
 * episodeNumber} checked against the episode being searched for before it's even scored. Without
 * that gate, the highest-scoring result for "Show S01E05" could easily be a different episode of
 * the same show that merely scores well on resolution/codec — silently grabbing the wrong hour of
 * television.
 *
 * <p>Episode-at-a-time, not season-pack-at-a-time: simpler to get right for a first pass, and it's
 * what most real per-episode indexer results are shaped like anyway. Season packs are a real gap
 * left for later.
 */
@ApplicationScoped
public class TvAutomaticSearchJob implements JobHandler {

  @Inject TorznabClient torznabClient;
  @Inject ReleaseParser releaseParser;
  @Inject ScoringEngine scoringEngine;
  @Inject QualityDefinitionService qualityDefinitionService;
  @Inject GrabService grabService;
  @Inject BlocklistService blocklistService;

  @Override
  public String jobName() {
    return "tv-automatic-search";
  }

  @Override
  public String displayName() {
    return "TV Automatic Search";
  }

  @Override
  public int defaultIntervalSeconds() {
    return 21600; // 6 hours, same cadence as the movie job
  }

  @Override
  public String run(ProgressReporter progress) {
    List<UUID> eligible = QuarkusTransaction.requiringNew().call(this::findEligibleEpisodeIds);
    for (UUID episodeId : eligible) {
      try {
        QuarkusTransaction.requiringNew().run(() -> searchAndGrab(episodeId));
      } catch (RuntimeException e) {
        // Left ungrabbed; retried on the next tick, same as the movie job.
      }
    }
    return null;
  }

  private List<UUID> findEligibleEpisodeIds() {
    return Episode.<Episode>list("season.show.qualityProfile is not null and monitored = true")
        .stream()
        .map(episode -> episode.mediaItemId)
        .filter(mediaItemId -> !Grab.hasActiveGrab(mediaItemId))
        .toList();
  }

  private void searchAndGrab(UUID episodeMediaItemId) {
    Episode episode = Episode.<Episode>findById(episodeMediaItemId);
    if (episode == null || episode.season.show.qualityProfile == null || !episode.monitored) {
      return;
    }
    Show show = episode.season.show;
    List<Indexer> indexers = Indexer.list("enabled", true);
    if (indexers.isEmpty()) {
      return;
    }
    DownloadClient client = DownloadClient.<DownloadClient>find("enabled", true).firstResult();
    if (client == null) {
      return;
    }

    String query =
        "%s S%02dE%02d"
            .formatted(show.mediaItem.title, episode.season.seasonNumber, episode.episodeNumber);

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
        ParsedRelease parsed = releaseParser.parse(raw.title());
        if (!matchesEpisode(parsed, episode)) {
          continue;
        }
        ScoredRelease scored = scoringEngine.score(parsed, show.qualityProfile);
        boolean sizeOk =
            qualityDefinitionService.checkSizeGate(parsed, raw.sizeBytes(), episode.runtimeMinutes)
                == null;
        if (scored.passesCutoff()
            && sizeOk
            && (best == null || scored.totalScore() > best.scored.totalScore())) {
          best = new Best(raw, parsed, scored);
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
            best.parsed.resolution(),
            best.parsed.source(),
            best.parsed.videoCodec(),
            best.scored.totalScore(),
            client.id));
  }

  private record Best(TorznabResult raw, ParsedRelease parsed, ScoredRelease scored) {}

  /**
   * The correctness gate the class doc describes — a release with no season/episode marker at all
   * never matches.
   */
  private boolean matchesEpisode(ParsedRelease parsed, Episode episode) {
    return parsed.seasonNumber() != null
        && parsed.seasonNumber() == episode.season.seasonNumber
        && parsed.episodeNumber() != null
        && parsed.episodeNumber() == episode.episodeNumber;
  }
}

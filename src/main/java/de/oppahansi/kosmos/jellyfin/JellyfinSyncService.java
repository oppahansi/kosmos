package de.oppahansi.kosmos.jellyfin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.oppahansi.kosmos.auth.User;
import de.oppahansi.kosmos.jellyfin.dto.JellyfinLibrarySyncResult;
import de.oppahansi.kosmos.jellyfin.dto.JellyfinUserSyncResult;
import de.oppahansi.kosmos.jellyfin.dto.SeasonEpisodeCount;
import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.library.LibraryRootFolder;
import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.library.ProbeService;
import de.oppahansi.kosmos.media.Anime;
import de.oppahansi.kosmos.media.AnimeEpisode;
import de.oppahansi.kosmos.media.AnimeService;
import de.oppahansi.kosmos.media.Episode;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.media.Movie;
import de.oppahansi.kosmos.media.Show;
import de.oppahansi.kosmos.media.ShowService;
import de.oppahansi.kosmos.metadata.MediaItemExternalId;
import de.oppahansi.kosmos.metadata.Plugin;
import de.oppahansi.kosmos.metadata.TitleMatching;
import de.oppahansi.kosmos.metadata.anilist.AniListAnimeDetails;
import de.oppahansi.kosmos.metadata.anilist.AniListMetadataProvider;
import de.oppahansi.kosmos.metadata.dto.MetadataSearchResult;
import de.oppahansi.kosmos.metadata.fribb.FribbEntry;
import de.oppahansi.kosmos.metadata.fribb.FribbMappingProvider;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reconciles an already-scanned Jellyfin library against Kosmos's own catalog: the
 * Jellyseerr/Overseerr pattern of reading a media server's own ProviderIds rather than
 * re-identifying anything from scratch. Movies whose TMDB id already exists in Kosmos get a
 * LibraryFile recorded against the existing MediaItem ("already available"); movies Kosmos has
 * never seen get a new MediaItem/Movie bulk-created. Only covers movies with both a Tmdb provider
 * id and a Path — everything else is skipped rather than guessed at. {@link #syncUsers} is a
 * separate operation — mirroring each Jellyfin account's admin flag into Kosmos — so libraries and
 * users can be scheduled, selected, and run independently.
 *
 * <p>A Jellyfin "tvshows" item routes to {@link Show} or {@link Anime} depending on {@link
 * #resolveAnimeMatchCandidates} — Jellyfin's own {@code CollectionType} can't tell them apart. Its
 * own {@code AniList} ProviderId (when an anime-metadata plugin populated one) is trusted first; a
 * reverse Fribb/anime-lists lookup by TMDB id is the fallback (see that method's own doc).
 *
 * <p>Every movie/show/anime/user gets its own transaction (like {@code DownloadStatusPollJob}):
 * this runs over a potentially large real library, and one bad item (a duplicate path, a colliding
 * username) must never roll back everything else already synced in the same run.
 */
@ApplicationScoped
public class JellyfinSyncService {

  private static final String TMDB_PLUGIN_SLUG = "tmdb";
  private static final String MATCH_METHOD = "JELLYFIN_SYNC";

  /**
   * Release year is an absolute fact, not a fuzzy signal — zero tolerance. Jellyfin's {@code
   * ProductionYear} field (despite the name) and AniList's {@code startDate.year} both mean the
   * same thing: the year the title actually released/aired, so they should simply agree.
   */
  private static final int YEAR_TOLERANCE = 0;

  @Inject ProbeService probeService;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject ShowService showService;
  @Inject AnimeService animeService;
  @Inject FribbMappingProvider fribbMappingProvider;
  @Inject JellyfinServerService serverService;
  @Inject AniListMetadataProvider aniListMetadataProvider;
  @Inject ObjectMapper objectMapper;

  public JellyfinLibrarySyncResult syncLibraries(UUID serverId, ProgressReporter progress) {
    JellyfinServer server = requireServer(serverId);
    // Same auto-register the setup wizard does explicitly, folded into every library sync —
    // otherwise a server added directly from Settings (skipping onboarding) or a library selected
    // after setup never gets a media folder registered for it at all, so newly-synced titles have
    // nowhere to be organized under.
    serverService.autoRegisterRootFolders(serverId);
    JellyfinClient client = new JellyfinClient(server.baseUrl);
    List<JellyfinMovie> movies;
    List<JellyfinShow> shows;
    List<JellyfinEpisode> episodes;
    try {
      movies = client.listMovies(server.apiKey, JellyfinServerService.selectedLibraryIds(server));
      shows = client.listShows(server.apiKey, JellyfinServerService.selectedLibraryIds(server));
      episodes =
          client.listEpisodes(server.apiKey, JellyfinServerService.selectedLibraryIds(server));
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Jellyfin server: " + e.getMessage());
    }

    int total = movies.size() + shows.size();
    int processed = 0;

    int linked = 0;
    int created = 0;
    int skipped = 0;
    int alreadySynced = 0;

    for (JellyfinMovie movie : movies) {
      processed++;
      progress.update(processed, total, "Movie: " + movie.name());
      if (movie.tmdbId() == null || movie.path() == null) {
        skipped++;
        continue;
      }
      try {
        String outcome =
            QuarkusTransaction.requiringNew().call(() -> syncOneMovie(serverId, movie));
        switch (outcome) {
          case "linked" -> linked++;
          case "created" -> created++;
          default -> alreadySynced++;
        }
      } catch (RuntimeException e) {
        alreadySynced++; // most likely a race on the same path; treat as already-handled
      }
    }

    Map<String, List<JellyfinEpisode>> episodesBySeriesId =
        episodes.stream()
            .filter(e -> e.seriesId() != null)
            .collect(Collectors.groupingBy(JellyfinEpisode::seriesId));

    // Resolves every show's Fribb match(es) up front and fetches all of them from AniList in one
    // batched pass (see AnimeService#fetchAniListDetails) instead of one AniList request per anime
    // inside the loop below — a large library can have hundreds of Fribb-matched shows, and AniList
    // rate-limits far below that per minute (see AniListMetadataProvider), so without batching most
    // of those requests would fail and silently drop the title from the sync entirely. Both a
    // show's primary and fallback candidate (see AnimeMatchCandidates) get their AniList ids into
    // this same batch, so classifyWithFallback's retry never needs its own extra fetch later.
    Map<String, AnimeMatchCandidates> animeCandidatesByShowId = new LinkedHashMap<>();
    for (JellyfinShow show : shows) {
      AnimeMatchCandidates candidates = resolveAnimeMatchCandidates(show);
      if (candidates.primary() != null) {
        animeCandidatesByShowId.put(show.id(), candidates);
      }
    }
    // Every sibling season of each candidate's franchise (see AnimeSeason) goes into the same
    // batch too, so a multi-season anime's own AnimeService#createFromJellyfin call never needs
    // its own extra AniList fetch for a season this run already had the TMDB TV id for.
    List<Integer> anilistIds =
        animeCandidatesByShowId.values().stream()
            .flatMap(c -> Stream.of(c.primary(), c.fallback()))
            .filter(Objects::nonNull)
            .flatMap(entry -> seasonChainAnilistIds(entry).stream())
            .distinct()
            .toList();
    Map<Integer, AniListAnimeDetails> anilistDetailsById =
        anilistIds.isEmpty() ? Map.of() : animeService.fetchAniListDetails(anilistIds);

    int showsLinked = 0;
    int showsCreated = 0;
    int showsSkipped = 0;
    int showsAlreadySynced = 0;
    int animeLinked = 0;
    int animeCreated = 0;
    int animeAlreadySynced = 0;
    int episodeFilesLinked = 0;
    int needsReview = 0;

    for (JellyfinShow show : shows) {
      processed++;
      progress.update(processed, total, "Show: " + show.name());
      if (show.tmdbId() == null || show.path() == null) {
        showsSkipped++;
        continue;
      }
      List<JellyfinEpisode> showEpisodes = episodesBySeriesId.getOrDefault(show.id(), List.of());
      ClassifiedAnimeMatch classified =
          classifyWithFallback(
              show,
              animeCandidatesByShowId.get(show.id()),
              anilistDetailsById,
              normalSeasonEpisodeCount(showEpisodes));
      FribbEntry animeMatch = classified.match();
      AniListAnimeDetails animeDetails = classified.details();
      MatchConfidence confidence = classified.confidence();
      // A title already synced as a Show keeps that identity rather than getting duplicated as a
      // separate Anime MediaItem — reclassifying would require migrating its episode structure
      // across two different episode models.
      boolean alreadyShow =
          animeMatch != null
              && QuarkusTransaction.requiringNew()
                  .call(() -> alreadySyncedAsShow(show.tmdbId(), show.year()));
      // A title a user already resolved as anime through the UnclassifiedShow review flow (see
      // #resolveAsAnime) keeps that identity on every later sync regardless of what this run's own
      // classification thinks — a human's earlier decision always outranks a fresh automatic guess.
      boolean alreadyAnime =
          animeMatch != null
              && QuarkusTransaction.requiringNew()
                  .call(() -> resolveExistingAnime(animeMatch, show.year()).isPresent());
      boolean tryAnime =
          animeMatch != null
              && !alreadyShow
              && (alreadyAnime || confidence == MatchConfidence.CONFIDENT);
      // flagUnclassified only ever upserts the pending-review row; nothing else clears it once a
      // show stops being ambiguous, so that has to happen here.
      boolean willFlagForReview =
          confidence == MatchConfidence.AMBIGUOUS && !alreadyShow && !alreadyAnime;
      if (!willFlagForReview) {
        QuarkusTransaction.requiringNew().run(() -> clearPendingReview(serverId, show.id()));
      }

      ShowSyncOutcome animeOutcome = null;
      if (tryAnime) {
        try {
          animeOutcome =
              QuarkusTransaction.requiringNew()
                  .call(() -> syncOneAnime(show, showEpisodes, animeMatch, anilistDetailsById));
        } catch (RuntimeException e) {
          // Most likely a bad Fribb match hitting an unrelated DB constraint. Falls through to
          // syncing as a regular Show below rather than losing the title from the library entirely
          // — a title is never worse than misclassified.
        }
      }

      if (animeOutcome != null) {
        switch (animeOutcome.outcome()) {
          case "linked" -> animeLinked++;
          case "created" -> animeCreated++;
          default -> animeAlreadySynced++;
        }
        episodeFilesLinked += animeOutcome.episodeFilesLinked();
        continue;
      }

      // AMBIGUOUS covers two cases: no AniList data to check the match against at all, or AniList
      // data that only partly agrees with what Jellyfin/TMDB reports for this item (year says yes,
      // title says no, or vice versa) — either way, guessing Show or Anime would be a coin flip,
      // not
      // a classification. REJECTED (both signals disagree) already fell through silently above,
      // since that's confidently NOT this match, not something worth a human's time.
      if (confidence == MatchConfidence.AMBIGUOUS && !alreadyShow && !alreadyAnime) {
        UnclassifiedShow.Reason reason =
            animeDetails == null
                ? UnclassifiedShow.Reason.ANILIST_MATCH_UNCONFIRMED
                : UnclassifiedShow.Reason.ANILIST_MATCH_AMBIGUOUS;
        QuarkusTransaction.requiringNew()
            .run(() -> flagUnclassified(serverId, show, showEpisodes, animeMatch, reason));
        needsReview++;
        continue;
      }

      try {
        ShowSyncOutcome outcome =
            QuarkusTransaction.requiringNew().call(() -> syncOneShow(show, showEpisodes));
        switch (outcome.outcome()) {
          case "linked" -> showsLinked++;
          case "created" -> showsCreated++;
          default -> showsAlreadySynced++;
        }
        episodeFilesLinked += outcome.episodeFilesLinked();
      } catch (RuntimeException e) {
        showsAlreadySynced++; // most likely a race, or this show's TMDB fetch failed; skip for now
      }
    }

    progress.update(total, total, "Done");
    return new JellyfinLibrarySyncResult(
        movies.size(),
        linked,
        created,
        skipped,
        alreadySynced,
        shows.size(),
        showsLinked,
        showsCreated,
        showsSkipped,
        showsAlreadySynced,
        animeLinked,
        animeCreated,
        animeAlreadySynced,
        episodeFilesLinked,
        needsReview);
  }

  /**
   * User-driven resolution of an {@link UnclassifiedShow} as a regular {@link Show} — the safe
   * default, since the TMDB identity Jellyfin reported is already trusted for every other show.
   * Re-fetches the item fresh from Jellyfin rather than trusting the snapshot taken when it was
   * flagged, which could be stale by review time.
   */
  @Transactional
  public void resolveAsShow(UUID pendingId) {
    UnclassifiedShow pending = requirePending(pendingId);
    JellyfinShow show = fetchLiveShow(pending);
    syncOneShow(show, fetchLiveEpisodes(pending, show));
    pending.delete();
  }

  /**
   * User-driven resolution as {@link Anime}, from an AniList id the user picked themselves (see
   * {@code MetadataResource#search}) rather than the one that failed to resolve automatically —
   * this also lets a user correct a stale/wrong Fribb mapping, not just confirm the original guess.
   */
  @Transactional
  public void resolveAsAnime(UUID pendingId, String anilistId) {
    UnclassifiedShow pending = requirePending(pendingId);
    JellyfinShow show = fetchLiveShow(pending);
    List<JellyfinEpisode> episodes = fetchLiveEpisodes(pending, show);

    AniListAnimeDetails details =
        aniListMetadataProvider
            .fetchById(anilistId)
            .orElseThrow(() -> new BadRequestException("AniList entry not found: " + anilistId));
    int parsedAnilistId;
    try {
      parsedAnilistId = Integer.parseInt(anilistId);
    } catch (NumberFormatException e) {
      throw new BadRequestException("Invalid AniList id: " + anilistId);
    }
    FribbEntry fribbEntry =
        fribbMappingProvider
            .loadMapping()
            .getOrDefault(
                parsedAnilistId,
                new FribbEntry(parsedAnilistId, null, null, null, null, null, null));

    syncOneAnime(show, episodes, fribbEntry, Map.of(parsedAnilistId, details));
    pending.delete();
  }

  /**
   * Dismisses an {@link UnclassifiedShow} without classifying it — it resurfaces on the next sync
   * if the same ambiguity still applies.
   */
  @Transactional
  public void dismissUnclassified(UUID pendingId) {
    requirePending(pendingId).delete();
  }

  private UnclassifiedShow requirePending(UUID pendingId) {
    return UnclassifiedShow.<UnclassifiedShow>findByIdOptional(pendingId)
        .orElseThrow(() -> new BadRequestException("Unknown unclassified show id: " + pendingId));
  }

  private JellyfinShow fetchLiveShow(UnclassifiedShow pending) {
    JellyfinClient client = new JellyfinClient(pending.jellyfinServer.baseUrl);
    try {
      return client
          .getShow(pending.jellyfinServer.apiKey, pending.jellyfinItemId)
          .orElseThrow(
              () -> new BadRequestException("Jellyfin no longer has this item: " + pending.name));
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Jellyfin server: " + e.getMessage());
    }
  }

  private List<JellyfinEpisode> fetchLiveEpisodes(UnclassifiedShow pending, JellyfinShow show) {
    JellyfinClient client = new JellyfinClient(pending.jellyfinServer.baseUrl);
    try {
      return client.listEpisodesForSeries(pending.jellyfinServer.apiKey, show.id());
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Jellyfin server: " + e.getMessage());
    }
  }

  /** Filtered by {@link JellyfinServer#selectedUserIds} — empty/null means every account. */
  public JellyfinUserSyncResult syncUsers(UUID serverId, ProgressReporter progress) {
    JellyfinServer server = requireServer(serverId);
    JellyfinClient client = new JellyfinClient(server.baseUrl);
    List<JellyfinUser> jellyfinUsers;
    try {
      jellyfinUsers = client.listUsers(server.apiKey);
    } catch (IOException | InterruptedException e) {
      throw new BadRequestException("Could not reach Jellyfin server: " + e.getMessage());
    }

    List<String> selected = JellyfinServerService.selectedUserIds(server);
    int total = jellyfinUsers.size();
    int processed = 0;
    int created = 0;
    int updated = 0;
    int skippedNotSelected = 0;

    for (JellyfinUser jellyfinUser : jellyfinUsers) {
      processed++;
      progress.update(processed, total, jellyfinUser.name());
      if (!selected.isEmpty() && !selected.contains(jellyfinUser.id())) {
        skippedNotSelected++;
        continue;
      }
      try {
        String outcome =
            QuarkusTransaction.requiringNew().call(() -> syncOneUser(serverId, jellyfinUser));
        switch (outcome) {
          case "created" -> created++;
          case "updated" -> updated++;
          default -> {} // "unchanged" — not worth reporting
        }
      } catch (RuntimeException e) {
        // e.g. this account's display name collides with an existing native username —
        // must never abort syncing the rest of the selected accounts.
      }
    }

    progress.update(total, total, "Done");
    return new JellyfinUserSyncResult(jellyfinUsers.size(), created, updated, skippedNotSelected);
  }

  private JellyfinServer requireServer(UUID serverId) {
    return QuarkusTransaction.requiringNew()
        .call(
            () ->
                JellyfinServer.<JellyfinServer>findByIdOptional(serverId)
                    .orElseThrow(
                        () -> new BadRequestException("Unknown Jellyfin server id: " + serverId)));
  }

  /**
   * @return "linked", "created", or "already-synced"
   */
  private String syncOneMovie(UUID serverId, JellyfinMovie movie) {
    Optional<LibraryFile> existingFile =
        LibraryFile.find("path", movie.path()).firstResultOptional();
    if (existingFile.isPresent()) {
      backfillIfIncomplete(existingFile.get().mediaItem, movie);
      return "already-synced";
    }

    Optional<MediaItemExternalId> existingLink =
        MediaItemExternalId.find(
                "plugin.slug = ?1 and externalId = ?2", TMDB_PLUGIN_SLUG, movie.tmdbId())
            .firstResultOptional();

    MediaItem mediaItem;
    String outcome;
    if (existingLink.isPresent()) {
      mediaItem = existingLink.get().mediaItem;
      outcome = "linked";
      backfillIfIncomplete(mediaItem, movie);
    } else {
      mediaItem = createMovie(movie);
      outcome = "created";
    }
    createLibraryFile(mediaItem, movie);
    return outcome;
  }

  /**
   * @return "linked", "created", or "already-synced" (mirrors {@link #syncOneMovie}) — plus how
   *     many new episode files got matched to this show's TMDB-built episode tree.
   */
  private ShowSyncOutcome syncOneShow(JellyfinShow show, List<JellyfinEpisode> episodes) {
    Optional<MediaItem> existing =
        resolveExistingMediaItem(TMDB_PLUGIN_SLUG, show.tmdbId(), "show", show.year());

    MediaItem mediaItem;
    boolean created;
    if (existing.isPresent()) {
      mediaItem = existing.get();
      created = false;
      if (mediaItem.rootFolder == null) {
        resolveRootFolder(show.path(), "show").ifPresent(folder -> mediaItem.rootFolder = folder);
      }
    } else {
      mediaItem = createShow(show);
      created = true;
    }

    int linkedFiles = linkEpisodeFiles(mediaItem, episodes);
    String outcome = created ? "created" : (linkedFiles > 0 ? "linked" : "already-synced");
    return new ShowSyncOutcome(outcome, linkedFiles);
  }

  private MediaItem createShow(JellyfinShow jellyfinShow) {
    LibraryRootFolder rootFolder = resolveRootFolder(jellyfinShow.path(), "show").orElse(null);
    Show show =
        showService.createFromExternalSource(
            jellyfinShow.name(), jellyfinShow.year(), jellyfinShow.tmdbId(), rootFolder);
    return show.mediaItem;
  }

  /**
   * The two independent anime signals for one show, kept separate rather than collapsed into a
   * single pick up front — see {@link #resolveAnimeMatchCandidates} and {@link
   * #classifyWithFallback}. {@code fallback} is only ever set to a genuinely different AniList id
   * than {@code primary}; when both signals agree (or only one exists), it's {@code null}.
   */
  private record AnimeMatchCandidates(FribbEntry primary, FribbEntry fallback) {}

  /**
   * Jellyfin's own {@code CollectionType} can't tell a "tvshows" item is anime — both are just
   * "tvshows". Two independent signals can identify it:
   *
   * <ol>
   *   <li>{@link JellyfinShow#anilistId}, populated only when the server has an anime-metadata
   *       plugin installed (e.g. Jellyfin.Plugin.AniList) — a direct identification, so it's the
   *       {@code primary} candidate whenever present. The matching {@link FribbEntry} (for its
   *       tvdb/mal/anidb/imdb cross-ids and TMDB season, used by {@code AnimeService}) is looked up
   *       by that same AniList id; if Fribb has no row for it, a bare entry carrying just the
   *       AniList id is still used rather than discarding Jellyfin's own identification.
   *   <li>A reverse lookup through Fribb/anime-lists' own TMDB-TV-id cross-reference: a hit means
   *       the anime-tracking community has independently matched this exact TMDB id to an AniList
   *       entry. When Jellyfin reported nothing, this is the {@code primary} candidate (the only
   *       option on a server with no anime plugin). When Jellyfin *did* report something, this
   *       becomes the {@code fallback} candidate instead — Jellyfin's own anime-metadata plugin can
   *       report a flatly wrong AniList id for a real title, and this reverse lookup is a second,
   *       independent opinion {@link #classifyWithFallback} can fall back to when the primary one
   *       turns out to be confidently wrong.
   * </ol>
   *
   * Both {@code loadMapping}/{@code loadMappingByTmdbTvId} are single cached bulk fetches (see
   * {@link FribbMappingProvider}), not per-show network calls, so checking every show costs nothing
   * extra per item.
   */
  private AnimeMatchCandidates resolveAnimeMatchCandidates(JellyfinShow show) {
    FribbEntry fromJellyfin = null;
    if (show.anilistId() != null) {
      try {
        int anilistId = Integer.parseInt(show.anilistId());
        FribbEntry entry = fribbMappingProvider.loadMapping().get(anilistId);
        fromJellyfin =
            entry != null ? entry : new FribbEntry(anilistId, null, null, null, null, null, null);
      } catch (NumberFormatException e) {
        // treated the same as Jellyfin reporting nothing usable
      }
    }
    FribbEntry fromTmdbReverse = null;
    if (show.tmdbId() != null) {
      try {
        fromTmdbReverse =
            fribbMappingProvider.loadMappingByTmdbTvId().get(Integer.parseInt(show.tmdbId()));
      } catch (NumberFormatException e) {
        // leave null
      }
    }
    if (fromJellyfin == null) {
      return new AnimeMatchCandidates(fromTmdbReverse, null);
    }
    boolean tmdbReverseAgrees =
        fromTmdbReverse != null && fromTmdbReverse.anilistId().equals(fromJellyfin.anilistId());
    return new AnimeMatchCandidates(fromJellyfin, tmdbReverseAgrees ? null : fromTmdbReverse);
  }

  /**
   * One show's classification outcome, carrying whichever candidate (primary or rescued fallback)
   * it came from — see {@link #classifyWithFallback}.
   */
  private record ClassifiedAnimeMatch(
      FribbEntry match, AniListAnimeDetails details, MatchConfidence confidence) {}

  /**
   * Classifies {@code candidates.primary()} first; if it comes back REJECTED (confidently the wrong
   * title, not just unconfirmed) and an independent {@code candidates.fallback()} exists, retries
   * with that instead — a different data source, unaffected by whatever caused the primary one to
   * be wrong. Only ever overrides a REJECTED primary, never an AMBIGUOUS or CONFIDENT one, and only
   * if the fallback itself doesn't also come back REJECTED.
   */
  private ClassifiedAnimeMatch classifyWithFallback(
      JellyfinShow show,
      AnimeMatchCandidates candidates,
      Map<Integer, AniListAnimeDetails> anilistDetailsById,
      int normalSeasonEpisodeCount) {
    if (candidates == null || candidates.primary() == null) {
      return new ClassifiedAnimeMatch(null, null, MatchConfidence.NONE);
    }
    FribbEntry primary = candidates.primary();
    AniListAnimeDetails primaryDetails = anilistDetailsById.get(primary.anilistId());
    MatchConfidence primaryConfidence =
        classifyAnimeMatch(show, primaryDetails, normalSeasonEpisodeCount);
    if (primaryConfidence != MatchConfidence.REJECTED || candidates.fallback() == null) {
      return new ClassifiedAnimeMatch(primary, primaryDetails, primaryConfidence);
    }
    FribbEntry fallback = candidates.fallback();
    AniListAnimeDetails fallbackDetails = anilistDetailsById.get(fallback.anilistId());
    MatchConfidence fallbackConfidence =
        classifyAnimeMatch(show, fallbackDetails, normalSeasonEpisodeCount);
    return fallbackConfidence == MatchConfidence.REJECTED
        ? new ClassifiedAnimeMatch(primary, primaryDetails, primaryConfidence)
        : new ClassifiedAnimeMatch(fallback, fallbackDetails, fallbackConfidence);
  }

  private enum MatchConfidence {
    /** No anime signal at all — the overwhelmingly common case for an ordinary show. */
    NONE,
    /** Year and title both corroborate the match — safe to auto-create as Anime. */
    CONFIDENT,
    /**
     * Signals are missing or disagree with each other — a human decides, see {@link
     * UnclassifiedShow}.
     */
    AMBIGUOUS,
    /**
     * Both signals disagree with the match — confidently not this title; falls back to Show
     * silently.
     */
    REJECTED
  }

  /**
   * How much to trust {@code details} as actually describing {@code show}, using AniList's start
   * year vs Jellyfin's release year and a title comparison. Jellyfin's own reported AniList id can
   * be flatly wrong with no title relationship to the real match — its anime-metadata plugin falls
   * back to AniList full-text search when it can't find an exact title, and that search also
   * matches on synopsis text.
   *
   * <p>{@code normalSeasonEpisodeCount} excludes season 0 (specials) before comparing against
   * AniList's episode total, since AniList tracks specials as a separate entry from the main
   * season. It's still only a rescue signal (can promote AMBIGUOUS to CONFIDENT, never reject), the
   * same as {@link TmdbMetadataProvider#showHasAnimeKeyword} below — a single AniList entry is
   * usually one cour, while a long-running show can span several Jellyfin seasons that map to
   * separate AniList entries, so a disagreement here doesn't prove anything on its own.
   *
   * <p>Title is the deciding signal for REJECTED, but only an *exact* title match earns the "never
   * confidently wrong" exemption — two titles can legitimately share a name while being different
   * works entirely (e.g. the 2023 live-action "ONE PIECE" vs the 1999 anime — see {@link
   * #resolveExistingMediaItem}). A merely *loose* match (one title contains the other after
   * stripping spaces/punctuation) is too easy to satisfy by coincidence for short/common titles
   * (e.g. "Vikings" contains "Viking"), so with a disagreeing year and neither rescue signal
   * confirming it, it's still a confident reject rather than a human's problem.
   */
  private MatchConfidence classifyAnimeMatch(
      JellyfinShow show, AniListAnimeDetails details, int normalSeasonEpisodeCount) {
    if (details == null) {
      return MatchConfidence.AMBIGUOUS;
    }
    boolean yearOk =
        details.startYear() == null
            || show.year() == null
            || Math.abs(details.startYear() - show.year()) <= YEAR_TOLERANCE;
    boolean titleLoose = TitleMatching.looselyMatch(show.name(), details.title());
    boolean titleExact = TitleMatching.exactlyMatch(show.name(), details.title());
    if (titleLoose && yearOk) {
      return MatchConfidence.CONFIDENT;
    }
    if (!titleLoose && !yearOk) {
      return MatchConfidence.REJECTED;
    }
    if (episodeCountConfirms(normalSeasonEpisodeCount, details.episodeCount())
        || (show.tmdbId() != null && tmdbMetadataProvider.showHasAnimeKeyword(show.tmdbId()))) {
      return MatchConfidence.CONFIDENT;
    }
    if (!yearOk && !titleExact) {
      return MatchConfidence.REJECTED;
    }
    return MatchConfidence.AMBIGUOUS;
  }

  /**
   * See {@link #classifyAnimeMatch}'s own doc for why this is a rescue-only signal, not a rejection
   * one.
   */
  private boolean episodeCountConfirms(int normalSeasonEpisodeCount, Integer anilistEpisodeCount) {
    if (normalSeasonEpisodeCount <= 0 || anilistEpisodeCount == null) {
      return false;
    }
    return Math.abs(normalSeasonEpisodeCount - anilistEpisodeCount) <= 1;
  }

  private boolean alreadySyncedAsShow(String tmdbId, Integer year) {
    return resolveExistingMediaItem(TMDB_PLUGIN_SLUG, tmdbId, "show", year).isPresent();
  }

  /**
   * Same idea as a plain "find the active link for this external id" lookup, but guards against two
   * physically distinct Jellyfin items that happen to report the same external id — a known
   * Jellyfin metadata bug (e.g. a live-action and an anime both titled "ONE PIECE" scraped to the
   * identical TMDB id) would otherwise get their episode files cross-linked into one MediaItem by
   * (season, episode) coincidence.
   *
   * <p>Every active link for this external id is checked against the newly-scanned item's own
   * reported release year; a mismatch (both years known, more than {@link #YEAR_TOLERANCE} apart)
   * means "this isn't actually the same title" rather than "already synced" — the caller creates a
   * fresh MediaItem instead of merging into the wrong one. A missing year on either side still
   * counts as a match, so existing yearless library entries keep behaving exactly as before.
   */
  private Optional<MediaItem> resolveExistingMediaItem(
      String pluginSlug, String externalId, String contentType, Integer year) {
    List<MediaItemExternalId> links =
        MediaItemExternalId.list(
            "plugin.slug = ?1 and externalId = ?2 and mediaItem.contentType = ?3"
                + " and supersededAt is null",
            pluginSlug,
            externalId,
            contentType);
    for (MediaItemExternalId link : links) {
      Integer existingYear = link.mediaItem.year;
      if (year == null || existingYear == null || Math.abs(year - existingYear) <= YEAR_TOLERANCE) {
        return Optional.of(link.mediaItem);
      }
    }
    return Optional.empty();
  }

  /**
   * Upserts the {@link UnclassifiedShow} pending-review row for {@code show} — a repeat sync of the
   * same still-unresolved item updates {@code detectedAt} rather than creating a duplicate row (see
   * the table's unique constraint on (jellyfin_server_id, jellyfin_item_id)). See {@link
   * #clearPendingReview} for the other half — removing this row once a later sync stops finding the
   * show ambiguous.
   */
  private void flagUnclassified(
      UUID serverId,
      JellyfinShow show,
      List<JellyfinEpisode> episodes,
      FribbEntry animeMatch,
      UnclassifiedShow.Reason reason) {
    UnclassifiedShow pending =
        UnclassifiedShow.<UnclassifiedShow>find(
                "jellyfinServer.id = ?1 and jellyfinItemId = ?2", serverId, show.id())
            .firstResultOptional()
            .orElseGet(UnclassifiedShow::new);
    pending.jellyfinServer = JellyfinServer.<JellyfinServer>findById(serverId);
    pending.jellyfinItemId = show.id();
    pending.name = show.name();
    pending.year = show.year();
    pending.tmdbId = show.tmdbId();
    pending.anilistId = String.valueOf(animeMatch.anilistId());
    pending.path = show.path();
    pending.reason = reason.name();
    pending.seasonsJson = seasonsJson(episodes);
    pending.detectedAt = Instant.now();
    pending.persist();
  }

  /** See {@link #flagUnclassified}'s doc — the other half of that upsert. */
  private void clearPendingReview(UUID serverId, String jellyfinItemId) {
    UnclassifiedShow.delete(
        "jellyfinServer.id = ?1 and jellyfinItemId = ?2", serverId, jellyfinItemId);
  }

  /**
   * Season 0 groups specials/episodes with no reported season number, matching Jellyfin/TMDB
   * convention. Returns {@code null} rather than an empty array when there's nothing to summarize,
   * so the column stays genuinely empty instead of storing {@code "[]"} for every plain show.
   */
  private String seasonsJson(List<JellyfinEpisode> episodes) {
    if (episodes.isEmpty()) {
      return null;
    }
    List<SeasonEpisodeCount> seasons =
        episodes.stream()
            .collect(Collectors.groupingBy(e -> e.seasonNumber() != null ? e.seasonNumber() : 0))
            .entrySet()
            .stream()
            .map(e -> new SeasonEpisodeCount(e.getKey(), e.getValue().size()))
            .sorted((a, b) -> Integer.compare(a.seasonNumber(), b.seasonNumber()))
            .toList();
    try {
      return objectMapper.writeValueAsString(seasons);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * Episode count from "normal" (non-special) seasons only — season 0 (specials/OVAs, same
   * convention as {@link #seasonsJson}) is excluded, since AniList's per-entry episode total never
   * includes those either. See {@link #classifyAnimeMatch}'s own doc for why this is still only a
   * rescue signal, not a full fix — a multi-cour show split across several Jellyfin seasons still
   * won't line up against a single AniList entry's count.
   */
  private int normalSeasonEpisodeCount(List<JellyfinEpisode> episodes) {
    return (int)
        episodes.stream().filter(e -> e.seasonNumber() != null && e.seasonNumber() != 0).count();
  }

  /** Same shape as {@link #syncOneShow}, routed to {@link Anime} instead of {@link Show}. */
  private ShowSyncOutcome syncOneAnime(
      JellyfinShow show,
      List<JellyfinEpisode> episodes,
      FribbEntry fribbEntry,
      Map<Integer, AniListAnimeDetails> anilistDetailsById) {
    Optional<MediaItem> existing = resolveExistingAnime(fribbEntry, show.year());

    MediaItem mediaItem;
    boolean created;
    if (existing.isPresent()) {
      mediaItem = existing.get();
      created = false;
      if (mediaItem.rootFolder == null) {
        resolveRootFolder(show.path(), "anime").ifPresent(folder -> mediaItem.rootFolder = folder);
      }
    } else {
      mediaItem = createAnime(show, fribbEntry, anilistDetailsById);
      created = true;
    }

    int linkedFiles = linkAnimeEpisodeFiles(mediaItem, episodes);
    String outcome = created ? "created" : (linkedFiles > 0 ? "linked" : "already-synced");
    return new ShowSyncOutcome(outcome, linkedFiles);
  }

  private MediaItem createAnime(
      JellyfinShow jellyfinShow,
      FribbEntry fribbEntry,
      Map<Integer, AniListAnimeDetails> anilistDetailsById) {
    LibraryRootFolder rootFolder = resolveRootFolder(jellyfinShow.path(), "anime").orElse(null);
    Anime anime =
        animeService.createFromJellyfin(
            jellyfinShow.name(), jellyfinShow.year(), fribbEntry, anilistDetailsById, rootFolder);
    return anime.mediaItem;
  }

  /**
   * An {@link Anime} is now a whole franchise (see {@link AnimeSeason}), not one AniList cour, so
   * dedup prefers its TMDB TV id — the same stable identity {@link #alreadySyncedAsShow} uses —
   * over the specific AniList id this one Jellyfin show happened to match, which might be any one
   * of the franchise's several seasons. Falls back to the AniList id when Fribb has no TMDB TV id
   * for this entry at all.
   */
  private Optional<MediaItem> resolveExistingAnime(FribbEntry fribbEntry, Integer year) {
    Integer tmdbTvId = fribbEntry.themoviedbId() != null ? fribbEntry.themoviedbId().tv() : null;
    if (tmdbTvId != null) {
      Optional<MediaItem> byTmdb =
          resolveExistingMediaItem(TMDB_PLUGIN_SLUG, String.valueOf(tmdbTvId), "anime", year);
      if (byTmdb.isPresent()) {
        return byTmdb;
      }
    }
    return resolveExistingMediaItem(
        "anilist", String.valueOf(fribbEntry.anilistId()), "anime", year);
  }

  /** {@code entry}'s own AniList id plus every sibling season's — see {@link AnimeSeason}'s doc. */
  private List<Integer> seasonChainAnilistIds(FribbEntry entry) {
    Integer tmdbTvId = entry.themoviedbId() != null ? entry.themoviedbId().tv() : null;
    if (tmdbTvId == null) {
      return List.of(entry.anilistId());
    }
    List<FribbEntry> siblings = fribbMappingProvider.seasonsForTmdbTvId(tmdbTvId);
    return siblings.isEmpty()
        ? List.of(entry.anilistId())
        : siblings.stream().map(FribbEntry::anilistId).toList();
  }

  /**
   * Matches each Jellyfin episode file to the Kosmos {@link Episode} at the same (season, episode)
   * number under this show — Jellyfin's own episode-level ProviderIds aren't reliably populated, so
   * season/episode number is the only stable join key available. An episode Jellyfin has but TMDB's
   * tree doesn't (e.g. an unindexed special) is silently skipped rather than guessed at.
   *
   * <p>Deduplicates by path first: Jellyfin can list the same physical file twice for one show
   * (e.g. the same folder swept into two overlapping libraries) — without this, two entries for the
   * same path would both pass the "not already linked" check below (neither is flushed to the DB
   * yet, so the second can't see the first), and the second insert would fail the {@code
   * library_file.path} unique constraint and abort this show's whole transaction.
   */
  private int linkEpisodeFiles(MediaItem showMediaItem, List<JellyfinEpisode> jellyfinEpisodes) {
    if (jellyfinEpisodes.isEmpty()) {
      return 0;
    }
    Show show = Show.<Show>findByIdOptional(showMediaItem.id).orElse(null);
    if (show == null) {
      return 0;
    }

    Map<String, JellyfinEpisode> byPath = new LinkedHashMap<>();
    for (JellyfinEpisode jellyfinEpisode : jellyfinEpisodes) {
      if (jellyfinEpisode.path() != null) {
        byPath.putIfAbsent(jellyfinEpisode.path(), jellyfinEpisode);
      }
    }

    int linked = 0;
    for (JellyfinEpisode jellyfinEpisode : byPath.values()) {
      if (jellyfinEpisode.seasonNumber() == null
          || jellyfinEpisode.episodeNumber() == null
          || jellyfinEpisode.path() == null) {
        continue;
      }
      Optional<Episode> episode =
          Episode.<Episode>find(
                  "season.show = ?1 and season.seasonNumber = ?2 and episodeNumber = ?3",
                  show,
                  jellyfinEpisode.seasonNumber(),
                  jellyfinEpisode.episodeNumber())
              .firstResultOptional();
      if (episode.isEmpty()) {
        continue;
      }
      if (LibraryFile.find("path", jellyfinEpisode.path()).firstResultOptional().isPresent()) {
        continue;
      }

      LibraryFile file = new LibraryFile();
      file.mediaItem = episode.get().mediaItem;
      file.path = jellyfinEpisode.path();
      file.sizeBytes = sizeOrZero(jellyfinEpisode.path());
      file.matchMethod = MATCH_METHOD;
      file.matchConfidence = 1.0f;
      file.matchPinned = false;
      file.matchedAt = Instant.now();
      file.verified = false;
      file.importedAt = Instant.now();
      probeService.tryProbe(file);
      file.persist();
      linked++;
    }
    return linked;
  }

  /**
   * Same idea as {@link #linkEpisodeFiles}, but {@link AnimeEpisode} carries no season — a Kosmos
   * {@link Anime} row is per-cour, matching one AniList entry's own episode count, so matching is
   * by episode number alone rather than (season, episode). This is correct for a single-cour anime
   * (the common case) but can misassign episodes for a multi-cour franchise if Jellyfin's own
   * season numbering doesn't line up with Kosmos's per-cour rows — reconciling that is a separate
   * problem from classifying the title as anime in the first place.
   */
  private int linkAnimeEpisodeFiles(
      MediaItem animeMediaItem, List<JellyfinEpisode> jellyfinEpisodes) {
    if (jellyfinEpisodes.isEmpty()) {
      return 0;
    }
    Anime anime = Anime.<Anime>findByIdOptional(animeMediaItem.id).orElse(null);
    if (anime == null) {
      return 0;
    }

    Map<String, JellyfinEpisode> byPath = new LinkedHashMap<>();
    for (JellyfinEpisode jellyfinEpisode : jellyfinEpisodes) {
      if (jellyfinEpisode.path() != null) {
        byPath.putIfAbsent(jellyfinEpisode.path(), jellyfinEpisode);
      }
    }

    int linked = 0;
    for (JellyfinEpisode jellyfinEpisode : byPath.values()) {
      if (jellyfinEpisode.episodeNumber() == null || jellyfinEpisode.path() == null) {
        continue;
      }
      Optional<AnimeEpisode> episode = resolveAnimeEpisode(anime, jellyfinEpisode);
      if (episode.isEmpty()) {
        continue;
      }
      if (LibraryFile.find("path", jellyfinEpisode.path()).firstResultOptional().isPresent()) {
        continue;
      }

      LibraryFile file = new LibraryFile();
      file.mediaItem = episode.get().mediaItem;
      file.path = jellyfinEpisode.path();
      file.sizeBytes = sizeOrZero(jellyfinEpisode.path());
      file.matchMethod = MATCH_METHOD;
      file.matchConfidence = 1.0f;
      file.matchPinned = false;
      file.matchedAt = Instant.now();
      file.verified = false;
      file.importedAt = Instant.now();
      probeService.tryProbe(file);
      file.persist();
      linked++;
    }
    return linked;
  }

  /**
   * Season-scoped join first — Jellyfin's own (season, episode) numbers against the matching {@link
   * AnimeSeason}, the same join a regular {@link Show}'s episodes already use — since Fribb's
   * {@code season.tmdb} ordering is meant to agree with however TMDB/Jellyfin itself numbers
   * seasons. Falls back to a match on {@code absoluteEpisodeNumber} for a library that instead
   * numbers everything under one flat season, a common alternate convention for long-running anime;
   * this is also what a purely episode-number join used to do for every anime before seasons
   * existed, which silently mismatched a multi-season show's second season onward (Jellyfin's own
   * episode 1 of season 2 colliding with season 1's episode 1).
   */
  private Optional<AnimeEpisode> resolveAnimeEpisode(Anime anime, JellyfinEpisode jellyfinEpisode) {
    if (jellyfinEpisode.seasonNumber() != null) {
      Optional<AnimeEpisode> bySeason =
          AnimeEpisode.<AnimeEpisode>find(
                  "season.anime = ?1 and season.seasonNumber = ?2 and episodeNumber = ?3",
                  anime,
                  jellyfinEpisode.seasonNumber(),
                  jellyfinEpisode.episodeNumber())
              .firstResultOptional();
      if (bySeason.isPresent()) {
        return bySeason;
      }
    }
    return AnimeEpisode.<AnimeEpisode>find(
            "season.anime = ?1 and absoluteEpisodeNumber = ?2",
            anime,
            jellyfinEpisode.episodeNumber())
        .firstResultOptional();
  }

  private record ShowSyncOutcome(String outcome, int episodeFilesLinked) {}

  /**
   * Fills in poster/root-folder for a previously-linked item still missing them (an incomplete
   * earlier import, or a run where the TMDB lookup failed) — every sync gets another chance, not
   * just brand-new rows.
   */
  private void backfillIfIncomplete(MediaItem mediaItem, JellyfinMovie jellyfinMovie) {
    Movie movie = Movie.<Movie>findByIdOptional(mediaItem.id).orElse(null);
    if (movie != null && (movie.posterPath == null || movie.posterPath.isBlank())) {
      enrichFromTmdb(movie, jellyfinMovie);
    }
    if (mediaItem.rootFolder == null) {
      resolveRootFolder(jellyfinMovie.path(), "movie")
          .ifPresent(folder -> mediaItem.rootFolder = folder);
    }
  }

  /**
   * @return "created", "updated" (role or display name actually changed), or "unchanged"
   */
  private String syncOneUser(UUID serverId, JellyfinUser jellyfinUser) {
    JellyfinServer server = JellyfinServer.<JellyfinServer>findById(serverId);
    String role = jellyfinUser.isAdmin() ? "ADMIN" : "USER";
    Optional<User> existing =
        User.find("jellyfinServer = ?1 and jellyfinUserId = ?2", server, jellyfinUser.id())
            .firstResultOptional();

    if (existing.isPresent()) {
      User user = existing.get();
      boolean changed = !role.equals(user.role) || !jellyfinUser.name().equals(user.displayName);
      user.role = role;
      user.displayName = jellyfinUser.name();
      return changed ? "updated" : "unchanged";
    }

    User user = new User();
    user.username = jellyfinUser.name();
    user.displayName = jellyfinUser.name();
    user.jellyfinServer = server;
    user.jellyfinUserId = jellyfinUser.id();
    user.role = role;
    user.enabled = true;
    user.createdAt = Instant.now();
    user.persist();
    return "created";
  }

  private MediaItem createMovie(JellyfinMovie jellyfinMovie) {
    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "movie";
    mediaItem.title = jellyfinMovie.name();
    mediaItem.year = jellyfinMovie.year();
    mediaItem.addedAt = Instant.now();
    resolveRootFolder(jellyfinMovie.path(), "movie")
        .ifPresent(folder -> mediaItem.rootFolder = folder);
    mediaItem.persist();

    Movie movie = new Movie();
    movie.mediaItem = mediaItem;
    movie.persist();
    Optional<String> trustedTmdbId = enrichFromTmdb(movie, jellyfinMovie);

    // Only link an id once it's actually corroborated — see #enrichFromTmdb. That id is usually
    // Jellyfin's own reported one, but can be a different, recovered one from the title+year search
    // fallback. Skipping the link entirely (not just the enrichment) when nothing corroborates
    // matters: persisting Jellyfin's wrong id anyway would make a later, genuinely different
    // Jellyfin item sharing that same wrong id resolve as "already synced" against this bare row.
    if (trustedTmdbId.isPresent()) {
      Plugin plugin = findOrCreateTmdbPlugin();
      MediaItemExternalId link = new MediaItemExternalId();
      link.mediaItem = mediaItem;
      link.plugin = plugin;
      link.externalId = trustedTmdbId.get();
      link.matchedAt = Instant.now();
      link.persist();
    }

    return mediaItem;
  }

  /**
   * Matches a 4-digit year in parentheses, e.g. the {@code (2021)} in {@code .../Belle
   * (2021)/file.mkv} — the de-facto Radarr/media-organizer folder-naming convention.
   */
  private static final Pattern PATH_YEAR_PATTERN = Pattern.compile("\\((\\d{4})\\)");

  /**
   * Jellyfin's ProviderIds give a TMDB id but no artwork/overview of its own — one extra TMDB round
   * trip per movie (cached 7 days, see application.properties) backfills what Kosmos's own poster
   * rendering needs.
   *
   * <p>Before trusting that data, cross-checks TMDB's own release year for {@code
   * jellyfinMovie.tmdbId()} against two independent years: Jellyfin's own reported year, and a year
   * parsed out of the file's own path (the {@code (YYYY)} folder-naming convention). Both are
   * needed because Jellyfin can resolve to a wrong TMDB id whose year still happens to agree with
   * its own reported year — the two Jellyfin-sourced fields can corroborate each other while both
   * being wrong. The path year is the one signal here not derived from Jellyfin's own (possibly
   * bad) identification at all.
   *
   * <p>On a mismatch, rather than just leaving the title bare, tries one recovery: a TMDB
   * title+year search using whichever independent year is available (path year preferred, since
   * it's the uncompromised signal) — see {@link TmdbMetadataProvider#searchMovieByTitleAndYear}.
   * Only a genuine failure to recover (no independent year to search with, or no matching search
   * result) falls all the way back to bare. A missing initial TMDB result (network failure,
   * unconfigured key) isn't itself distrust — there's nothing to contradict Jellyfin's report, so
   * it's still trusted for linking.
   *
   * @return the TMDB id that's safe to link this movie to, if any — Jellyfin's own reported one
   *     once corroborated, a recovered one from the search fallback, or empty if neither panned out
   */
  private Optional<String> enrichFromTmdb(Movie movie, JellyfinMovie jellyfinMovie) {
    Optional<MetadataSearchResult> result =
        tmdbMetadataProvider.fetchMovieById(jellyfinMovie.tmdbId());
    Integer pathYear = extractYearFromPath(jellyfinMovie.path());
    if (result.isPresent()) {
      MetadataSearchResult r = result.get();
      boolean jellyfinYearDisagrees =
          jellyfinMovie.year() != null
              && r.year() != null
              && !jellyfinMovie.year().equals(r.year());
      boolean pathYearDisagrees =
          pathYear != null && r.year() != null && !pathYear.equals(r.year());
      if (!jellyfinYearDisagrees && !pathYearDisagrees) {
        movie.posterPath = r.posterPath();
        movie.backdropPath = r.backdropPath();
        movie.overview = r.overview();
        movie.releaseDate = r.releaseDateAsLocalDate();
        return Optional.of(jellyfinMovie.tmdbId());
      }
    } else {
      return Optional.of(jellyfinMovie.tmdbId());
    }

    Integer trustedYear = pathYear != null ? pathYear : jellyfinMovie.year();
    if (trustedYear == null) {
      return Optional.empty();
    }
    Optional<MetadataSearchResult> recovered =
        tmdbMetadataProvider.searchMovieByTitleAndYear(jellyfinMovie.name(), trustedYear);
    if (recovered.isEmpty()) {
      return Optional.empty();
    }
    MetadataSearchResult r = recovered.get();
    movie.posterPath = r.posterPath();
    movie.backdropPath = r.backdropPath();
    movie.overview = r.overview();
    movie.releaseDate = r.releaseDateAsLocalDate();
    return Optional.of(r.externalId());
  }

  /**
   * Last {@code (YYYY)} match in the path wins — closest to the filename, so least likely to be
   * some other coincidental 4-digit folder name higher up the tree.
   */
  private Integer extractYearFromPath(String path) {
    if (path == null) {
      return null;
    }
    Matcher matcher = PATH_YEAR_PATTERN.matcher(path);
    Integer last = null;
    while (matcher.find()) {
      last = Integer.valueOf(matcher.group(1));
    }
    return last;
  }

  /**
   * Prefers the registered root folder Jellyfin's own reported path actually falls under (see
   * {@link de.oppahansi.kosmos.library.LibraryRootFolderService#findContaining}) — e.g. a movie
   * under {@code /media/anime-movies} lands under that folder specifically, not just "the movies
   * default" — falling back to the {@code contentType} default only if none of the registered
   * folders actually contain this path.
   */
  private Optional<LibraryRootFolder> resolveRootFolder(String path, String contentType) {
    Optional<LibraryRootFolder> containing = rootFolderService.findContaining(path);
    return containing.isPresent() ? containing : rootFolderService.getDefault(contentType);
  }

  private void createLibraryFile(MediaItem mediaItem, JellyfinMovie jellyfinMovie) {
    LibraryFile file = new LibraryFile();
    file.mediaItem = mediaItem;
    file.path = jellyfinMovie.path();
    file.sizeBytes = sizeOrZero(jellyfinMovie.path());
    file.matchMethod = MATCH_METHOD;
    file.matchConfidence = 1.0f;
    file.matchPinned = false;
    file.matchedAt = Instant.now();
    file.verified = false;
    file.importedAt = Instant.now();
    probeService.tryProbe(file); // best-effort — only succeeds if Kosmos can see this path too
    file.persist();
  }

  private long sizeOrZero(String path) {
    try {
      return Files.size(Path.of(path));
    } catch (Exception e) {
      return 0L;
    }
  }

  private Plugin findOrCreateTmdbPlugin() {
    return Plugin.<Plugin>find("slug", TMDB_PLUGIN_SLUG)
        .firstResultOptional()
        .orElseGet(
            () -> {
              Plugin plugin = new Plugin();
              plugin.slug = TMDB_PLUGIN_SLUG;
              plugin.name = "TMDB";
              plugin.kind = "metadata";
              plugin.builtIn = true;
              plugin.enabled = true;
              plugin.installedAt = Instant.now();
              plugin.persist();
              return plugin;
            });
  }
}

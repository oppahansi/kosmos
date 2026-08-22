package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.library.LibraryRootFolder;
import de.oppahansi.kosmos.library.LibraryRootFolderService;
import de.oppahansi.kosmos.media.dto.CreateAnimeRequest;
import de.oppahansi.kosmos.metadata.ExternalIdLinkService;
import de.oppahansi.kosmos.metadata.SimilarEnrichmentService;
import de.oppahansi.kosmos.metadata.anilist.AniListAnimeDetails;
import de.oppahansi.kosmos.metadata.anilist.AniListMetadataProvider;
import de.oppahansi.kosmos.metadata.dto.MediaDetailExtras;
import de.oppahansi.kosmos.metadata.dto.MediaPreview;
import de.oppahansi.kosmos.metadata.fribb.FribbEntry;
import de.oppahansi.kosmos.metadata.fribb.FribbMappingProvider;
import de.oppahansi.kosmos.metadata.thexem.TheXemMappingProvider;
import de.oppahansi.kosmos.metadata.tmdb.TmdbMetadataProvider;
import de.oppahansi.kosmos.metadata.tmdb.TmdbShowStructure;
import de.oppahansi.kosmos.parsing.AnimeReleaseParser;
import de.oppahansi.kosmos.parsing.QualityProfileService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class AnimeService {

  @Inject QualityProfileService qualityProfileService;
  @Inject LibraryRootFolderService rootFolderService;
  @Inject AniListMetadataProvider aniListMetadataProvider;
  @Inject FribbMappingProvider fribbMappingProvider;
  @Inject TheXemMappingProvider theXemMappingProvider;
  @Inject TmdbMetadataProvider tmdbMetadataProvider;
  @Inject ExternalIdLinkService externalIdLinkService;
  @Inject SimilarEnrichmentService similarEnrichmentService;

  public List<Anime> listAll() {
    return Anime.listAll();
  }

  /**
   * Passthrough to {@link AniListMetadataProvider#fetchByIds} — {@code JellyfinSyncService} batches
   * every Fribb-matched anime id from a whole sync run through here up front, rather than each
   * {@link #createFromJellyfin} call triggering its own AniList request.
   */
  public Map<Integer, AniListAnimeDetails> fetchAniListDetails(List<Integer> anilistIds) {
    return aniListMetadataProvider.fetchByIds(anilistIds);
  }

  public Optional<Anime> findById(UUID id) {
    return Anime.findByIdOptional(id);
  }

  public List<AnimeSeason> seasonsFor(UUID animeId) {
    return AnimeSeason.list("anime.mediaItemId = ?1 order by seasonNumber", animeId);
  }

  public List<AnimeEpisode> episodesFor(UUID seasonId) {
    return AnimeEpisode.list("season.id = ?1 order by episodeNumber", seasonId);
  }

  /**
   * Unlike {@link MovieService#create}, the AniList fetch isn't best-effort — an anime with no
   * episode count isn't a useful entry to have added, so a fetch failure fails the whole creation
   * rather than leaving an empty shell (same reasoning {@link ShowService#create} uses for TMDB).
   * The season/episode tree comes from {@link #resolveSeasonChain}/{@link #persistSeasons} — see
   * their own docs.
   */
  @Transactional
  public Anime create(CreateAnimeRequest request) {
    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "anime";
    mediaItem.title = request.title();
    mediaItem.year = request.year();
    mediaItem.addedAt = Instant.now();
    mediaItem.rootFolder =
        rootFolderService.resolveOrDefault(request.rootFolderId(), "anime").orElse(null);
    mediaItem.persist();

    Anime anime = new Anime();
    anime.mediaItem = mediaItem;
    anime.overview = request.overview();
    anime.posterPath = request.posterPath();
    anime.backdropPath = request.backdropPath();
    anime.qualityProfile = qualityProfileService.resolveOrThrow(request.qualityProfileId());

    if ("anilist".equals(request.pluginSlug()) && request.externalId() != null) {
      int anilistId;
      try {
        anilistId = Integer.parseInt(request.externalId());
      } catch (NumberFormatException e) {
        throw new BadRequestException("Invalid AniList id: " + request.externalId());
      }
      FribbEntry entry =
          resolveFribbEntry(request.externalId())
              .orElse(new FribbEntry(anilistId, null, null, null, null, null, null));
      List<FribbEntry> seasonChain = resolveSeasonChain(entry);
      Map<Integer, AniListAnimeDetails> detailsById =
          withSeasonDetails(seasonChain, aniListMetadataProvider.fetchByIds(List.of(anilistId)));
      AniListAnimeDetails primaryDetails = detailsById.get(anilistId);
      if (primaryDetails == null) {
        throw new BadRequestException("AniList entry not found: " + request.externalId());
      }
      anime.status = primaryDetails.status();
      anime.persist();
      linkFribbExternalIds(mediaItem, entry);
      anime.episodeCountTotal = persistSeasons(anime, seasonChain, detailsById);
      linkTmdbTvId(mediaItem, entry);
    } else {
      anime.persist();
    }

    if (request.externalId() != null && request.pluginSlug() != null) {
      externalIdLinkService.link(mediaItem, request.pluginSlug(), request.externalId());
    }

    return anime;
  }

  /**
   * Used by {@code JellyfinSyncService} — same AniList-driven season/episode tree as {@link
   * #create}, but from a server-reported title/year/root-folder and an already-resolved {@link
   * FribbEntry} (found by reverse Fribb lookup on the Jellyfin item's TMDB id) rather than a
   * user-submitted request, and with no quality profile assigned — matches Jellyfin-synced
   * movies/shows, which are also unmonitored until the user assigns one. {@code anilistDetailsById}
   * is pre-fetched by the caller via {@code AniListMetadataProvider#fetchByIds} — a whole sync's
   * AniList lookups are batched up front rather than one {@link AniListMetadataProvider#fetchById}
   * call per anime, which is what let a large library outrun AniList's rate limit and silently drop
   * titles; {@link #withSeasonDetails} only falls back to its own extra fetch for a sibling season
   * the caller's batch didn't already cover.
   */
  @Transactional
  public Anime createFromJellyfin(
      String title,
      Integer year,
      FribbEntry fribbEntry,
      Map<Integer, AniListAnimeDetails> anilistDetailsById,
      LibraryRootFolder rootFolder) {
    List<FribbEntry> seasonChain = resolveSeasonChain(fribbEntry);
    Map<Integer, AniListAnimeDetails> detailsById =
        withSeasonDetails(seasonChain, anilistDetailsById);
    // The matched entry's own details are guaranteed present — that's what got this show
    // classified in the first place — so they're the fallback if the canonical (earliest
    // non-specials) season's own AniList fetch came back empty.
    AniListAnimeDetails matchedDetails = detailsById.get(fribbEntry.anilistId());
    AniListAnimeDetails canonicalDetails =
        canonicalSeasonDetails(seasonChain, detailsById, matchedDetails);

    MediaItem mediaItem = new MediaItem();
    mediaItem.contentType = "anime";
    mediaItem.title = title;
    mediaItem.year = year;
    mediaItem.addedAt = Instant.now();
    mediaItem.rootFolder = rootFolder;
    mediaItem.persist();

    Anime anime = new Anime();
    anime.mediaItem = mediaItem;
    anime.overview = canonicalDetails.overview();
    anime.posterPath = canonicalDetails.posterPath();
    anime.status = canonicalDetails.status();
    anime.persist();

    linkFribbExternalIds(mediaItem, fribbEntry);
    anime.episodeCountTotal = persistSeasons(anime, seasonChain, detailsById);
    linkTmdbTvId(mediaItem, fribbEntry);

    externalIdLinkService.link(mediaItem, "anilist", String.valueOf(fribbEntry.anilistId()));
    return anime;
  }

  /**
   * Every AniList cour in {@code fribbEntry}'s franchise, ordered by TMDB season number — see
   * {@link AnimeSeason}'s own doc for why Fribb/anime-lists' TMDB cross-reference is the source of
   * this instead of AniList (which has no season concept) or Jellyfin (whose own season folders
   * aren't trusted here, per the anime data model's design). Falls back to a single-entry "season 1
   * only" chain when {@code fribbEntry} has no TMDB TV id to group siblings by.
   */
  private List<FribbEntry> resolveSeasonChain(FribbEntry fribbEntry) {
    Integer tmdbTvId = fribbEntry.themoviedbId() != null ? fribbEntry.themoviedbId().tv() : null;
    if (tmdbTvId == null) {
      return List.of(fribbEntry);
    }
    List<FribbEntry> chain = fribbMappingProvider.seasonsForTmdbTvId(tmdbTvId);
    return chain.isEmpty() ? List.of(fribbEntry) : chain;
  }

  /**
   * {@code alreadyFetched} plus a single extra batched fetch for whichever season ids it's missing.
   */
  private Map<Integer, AniListAnimeDetails> withSeasonDetails(
      List<FribbEntry> seasonChain, Map<Integer, AniListAnimeDetails> alreadyFetched) {
    List<Integer> missing =
        seasonChain.stream()
            .map(FribbEntry::anilistId)
            .filter(id -> !alreadyFetched.containsKey(id))
            .toList();
    if (missing.isEmpty()) {
      return alreadyFetched;
    }
    Map<Integer, AniListAnimeDetails> merged = new LinkedHashMap<>(alreadyFetched);
    merged.putAll(aniListMetadataProvider.fetchByIds(missing));
    return merged;
  }

  /**
   * The franchise's own poster/overview/status come from its earliest real (non-specials) season —
   * {@code fallback} covers the rare case where that particular season's own AniList fetch came
   * back empty even though the chain includes it.
   */
  private AniListAnimeDetails canonicalSeasonDetails(
      List<FribbEntry> seasonChain,
      Map<Integer, AniListAnimeDetails> detailsById,
      AniListAnimeDetails fallback) {
    FribbEntry canonical =
        seasonChain.stream()
            .filter(e -> e.season() != null && e.season().tmdb() != null && e.season().tmdb() >= 1)
            .findFirst()
            .orElse(seasonChain.get(0));
    AniListAnimeDetails details = detailsById.get(canonical.anilistId());
    return details != null ? details : fallback;
  }

  private void linkTmdbTvId(MediaItem mediaItem, FribbEntry entry) {
    Integer tmdbTvId = entry.themoviedbId() != null ? entry.themoviedbId().tv() : null;
    if (tmdbTvId != null) {
      externalIdLinkService.link(mediaItem, "tmdb", String.valueOf(tmdbTvId));
    }
  }

  private Optional<FribbEntry> resolveFribbEntry(String anilistExternalId) {
    try {
      int anilistId = Integer.parseInt(anilistExternalId);
      return Optional.ofNullable(fribbMappingProvider.loadMapping().get(anilistId));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private FribbEnrichment fribbEnrichmentFrom(FribbEntry entry) {
    if (entry == null) {
      return new FribbEnrichment(List.of(), null);
    }
    Integer tmdbTvId = entry.themoviedbId() != null ? entry.themoviedbId().tv() : null;
    Integer tmdbSeason = entry.season() != null ? entry.season().tmdb() : null;
    List<TmdbShowStructure.EpisodeData> episodes =
        tmdbTvId == null || tmdbSeason == null
            ? List.of()
            : tmdbMetadataProvider.fetchSeasonEpisodes(String.valueOf(tmdbTvId), tmdbSeason);
    return new FribbEnrichment(episodes, entry.anidbId());
  }

  /** TMDB episode metadata plus the AniDB id TheXEM's scene-numbering lookup is keyed on. */
  private record FribbEnrichment(List<TmdbShowStructure.EpisodeData> episodes, Integer anidbId) {}

  private void linkFribbExternalIds(MediaItem mediaItem, FribbEntry entry) {
    if (entry.tvdbId() != null) {
      externalIdLinkService.link(mediaItem, "tvdb", String.valueOf(entry.tvdbId()));
    }
    if (entry.malId() != null) {
      externalIdLinkService.link(mediaItem, "mal", String.valueOf(entry.malId()));
    }
    if (entry.anidbId() != null) {
      externalIdLinkService.link(mediaItem, "anidb", String.valueOf(entry.anidbId()));
    }
    if (entry.imdbId() != null && !entry.imdbId().isEmpty()) {
      externalIdLinkService.link(mediaItem, "imdb", entry.imdbId().get(0));
    }
  }

  @Transactional
  public Optional<Anime> updateQualityProfile(UUID animeId, UUID qualityProfileId) {
    return findById(animeId)
        .map(
            anime -> {
              anime.qualityProfile = qualityProfileService.resolveOrThrow(qualityProfileId);
              return anime;
            });
  }

  /** Same idea as {@code ShowService#updateMonitoring} — see its own doc. */
  @Transactional
  public boolean updateMonitoring(UUID animeId, String mode, Integer seasonNumber) {
    if (Anime.count("mediaItemId", animeId) == 0) {
      return false;
    }
    String seasonFilter = seasonNumber != null ? " and season.seasonNumber = ?2" : "";
    Object[] baseParams =
        seasonNumber != null ? new Object[] {animeId, seasonNumber} : new Object[] {animeId};
    switch (mode) {
      case "ALL" ->
          AnimeEpisode.update(
              "monitored = true where season.anime.mediaItemId = ?1" + seasonFilter, baseParams);
      case "NONE" ->
          AnimeEpisode.update(
              "monitored = false where season.anime.mediaItemId = ?1" + seasonFilter, baseParams);
      case "FUTURE" -> {
        LocalDate today = LocalDate.now();
        AnimeEpisode.update(
            "monitored = true where season.anime.mediaItemId = ?1"
                + seasonFilter
                + " and (airDate is null or airDate >= ?"
                + (baseParams.length + 1)
                + ")",
            append(baseParams, today));
        AnimeEpisode.update(
            "monitored = false where season.anime.mediaItemId = ?1"
                + seasonFilter
                + " and airDate is not null and airDate < ?"
                + (baseParams.length + 1),
            append(baseParams, today));
      }
      case "MISSING" -> {
        AnimeEpisode.update(
            "monitored = true where season.anime.mediaItemId = ?1"
                + seasonFilter
                + " and mediaItemId not in (select f.mediaItem.id from LibraryFile f where"
                + " f.mediaItem is not null)",
            baseParams);
        AnimeEpisode.update(
            "monitored = false where season.anime.mediaItemId = ?1"
                + seasonFilter
                + " and mediaItemId in (select f.mediaItem.id from LibraryFile f where"
                + " f.mediaItem is not null)",
            baseParams);
      }
      default -> throw new BadRequestException("Unknown monitoring mode: " + mode);
    }
    return true;
  }

  private Object[] append(Object[] params, Object extra) {
    Object[] result = Arrays.copyOf(params, params.length + 1);
    result[params.length] = extra;
    return result;
  }

  /** Genres/similar for the detail page — see {@link AniListMetadataProvider#fetchDetailExtras}. */
  public Optional<MediaDetailExtras> detailExtras(UUID animeId) {
    return externalIdLinkService
        .findActiveExternalId(animeId, "anilist")
        .flatMap(aniListMetadataProvider::fetchDetailExtras)
        .map(
            extras ->
                extras.withSimilar(
                    similarEnrichmentService.enrich(extras.similar(), "anilist", "anime")));
  }

  /**
   * Same idea as {@link #detailExtras}, but for an AniList entry Kosmos has no {@link Anime} row
   * for yet — backs the detail screen a not-in-library card links to, so it opens something real
   * instead of falling back to a search. AniList's by-id lookup carries no backdrop or release year
   * of its own (see {@link AniListAnimeDetails}), so those are left null here rather than guessed
   * at — the "First Aired" fact from {@code extras} still surfaces the year in text form.
   */
  public Optional<MediaPreview> preview(String externalId) {
    Optional<AniListAnimeDetails> base = aniListMetadataProvider.fetchById(externalId);
    if (base.isEmpty()) {
      return Optional.empty();
    }
    AniListAnimeDetails b = base.get();
    MediaDetailExtras extras =
        aniListMetadataProvider
            .fetchDetailExtras(externalId)
            .map(
                e ->
                    e.withSimilar(similarEnrichmentService.enrich(e.similar(), "anilist", "anime")))
            .orElse(
                new MediaDetailExtras(
                    List.of(), List.of(), null, null, null, List.of(), List.of(), null, null));
    return Optional.of(
        new MediaPreview(
            externalId,
            "anilist",
            "anime",
            b.title(),
            null,
            b.overview(),
            b.posterPath(),
            null,
            extras.genres(),
            extras.facts(),
            extras.voteAverage(),
            extras.voteCount(),
            extras.certification(),
            extras.cast(),
            extras.similar(),
            previewSeasons(externalId, b),
            List.of(),
            extras.trailerUrl()));
  }

  /**
   * Same season chain and Fribb/TMDB episode enrichment {@link #createFromJellyfin}/{@link #create}
   * persist, reused read-only for the not-owned preview screen so it can render the identical
   * Seasons section an owned anime's detail page does. Best-effort throughout: any lookup failure
   * mid-chain falls back to just {@code base} as a single season, never a broken preview.
   */
  private List<MediaPreview.PreviewSeason> previewSeasons(
      String anilistExternalId, AniListAnimeDetails base) {
    int anilistId;
    try {
      anilistId = Integer.parseInt(anilistExternalId);
    } catch (NumberFormatException e) {
      return List.of();
    }
    List<FribbEntry> seasonChain;
    Map<Integer, AniListAnimeDetails> detailsById;
    try {
      FribbEntry entry =
          resolveFribbEntry(anilistExternalId)
              .orElse(new FribbEntry(anilistId, null, null, null, null, null, null));
      seasonChain = resolveSeasonChain(entry);
      detailsById = withSeasonDetails(seasonChain, Map.of(anilistId, base));
    } catch (Exception e) {
      seasonChain = List.of(new FribbEntry(anilistId, null, null, null, null, null, null));
      detailsById = Map.of(anilistId, base);
    }

    // Same season-number grouping as persistSeasons — see that method's own doc for why multiple
    // chain entries can share one TMDB season number (a "Part 1"/"Part 2" split TMDB itself
    // doesn't distinguish).
    Map<Integer, List<FribbEntry>> partsBySeasonNumber = new LinkedHashMap<>();
    for (FribbEntry entry : seasonChain) {
      AniListAnimeDetails details = detailsById.get(entry.anilistId());
      if (details == null || details.episodeCount() == null || details.episodeCount() <= 0) {
        continue;
      }
      int seasonNumber =
          entry.season() != null && entry.season().tmdb() != null ? entry.season().tmdb() : 1;
      partsBySeasonNumber
          .computeIfAbsent(seasonNumber, k -> new java.util.ArrayList<>())
          .add(entry);
    }

    List<MediaPreview.PreviewSeason> out = new java.util.ArrayList<>();
    int offset = 0;
    for (Map.Entry<Integer, List<FribbEntry>> seasonParts : partsBySeasonNumber.entrySet()) {
      int seasonNumber = seasonParts.getKey();
      int seasonOffset = seasonNumber == 0 ? 0 : offset;

      List<MediaPreview.PreviewEpisode> episodes = new java.util.ArrayList<>();
      int withinSeasonOffset = 0;
      for (FribbEntry part : seasonParts.getValue()) {
        AniListAnimeDetails details = detailsById.get(part.anilistId());
        FribbEnrichment enrichment;
        try {
          enrichment = fribbEnrichmentFrom(part);
        } catch (Exception e) {
          enrichment = new FribbEnrichment(List.of(), null);
        }
        for (int i = 1; i <= details.episodeCount(); i++) {
          int episodeNumber = i;
          TmdbShowStructure.EpisodeData tmdbEpisode =
              enrichment.episodes().stream()
                  .filter(e -> e.episodeNumber() == episodeNumber)
                  .findFirst()
                  .orElse(null);
          String title =
              tmdbEpisode != null && tmdbEpisode.title() != null
                  ? tmdbEpisode.title()
                  : "Episode " + i;
          int withinCourNumber =
              enrichment.anidbId() == null
                  ? i
                  : theXemMappingProvider
                      .sceneAbsoluteForAnidbEpisode(enrichment.anidbId(), i)
                      .orElse(i);
          episodes.add(
              new MediaPreview.PreviewEpisode(
                  withinSeasonOffset + i,
                  seasonOffset + withinSeasonOffset + withinCourNumber,
                  title,
                  tmdbEpisode != null ? tmdbEpisode.airDate() : null));
        }
        withinSeasonOffset += details.episodeCount();
      }
      out.add(
          new MediaPreview.PreviewSeason(
              seasonNumber,
              seasonNumber == 0 ? "Specials" : "Season " + seasonNumber,
              withinSeasonOffset,
              episodes));
      if (seasonNumber != 0) {
        offset += withinSeasonOffset;
      }
    }
    return out;
  }

  /**
   * Creates one {@link AnimeSeason} per distinct TMDB season number in the chain — TMDB (and so
   * Fribb, which cross-references it) doesn't always split a "Part 1"/"Part 2" cour pair the way
   * AniList does, so multiple chain entries can legitimately share one season number; those are
   * merged into a single season whose episodes run Part 1 then Part 2 in chain order, the same
   * shape a TMDB show's own season would have. Each season is offset by every non-specials season
   * before it, so {@code absoluteEpisodeNumber} runs continuously across the whole franchise.
   * Season 0 (specials) is excluded from that running offset and numbers its own episodes from 1,
   * same as TMDB does for shows. A chain entry the AniList batch has nothing for is silently
   * skipped, same best-effort spirit as the enrichment below.
   *
   * @return the franchise's total real episode count across every persisted season, or {@code null}
   *     if none had one (mirrors the old flat model's "nothing to persist" case).
   */
  private Integer persistSeasons(
      Anime anime, List<FribbEntry> seasonChain, Map<Integer, AniListAnimeDetails> detailsById) {
    Map<Integer, List<FribbEntry>> partsBySeasonNumber = new LinkedHashMap<>();
    for (FribbEntry entry : seasonChain) {
      AniListAnimeDetails details = detailsById.get(entry.anilistId());
      if (details == null || details.episodeCount() == null || details.episodeCount() <= 0) {
        continue;
      }
      int seasonNumber =
          entry.season() != null && entry.season().tmdb() != null ? entry.season().tmdb() : 1;
      partsBySeasonNumber
          .computeIfAbsent(seasonNumber, k -> new java.util.ArrayList<>())
          .add(entry);
    }

    int offset = 0;
    int total = 0;
    boolean anyEpisodes = false;
    for (Map.Entry<Integer, List<FribbEntry>> seasonParts : partsBySeasonNumber.entrySet()) {
      int seasonNumber = seasonParts.getKey();
      List<FribbEntry> parts = seasonParts.getValue();
      int seasonEpisodeCount =
          parts.stream().mapToInt(e -> detailsById.get(e.anilistId()).episodeCount()).sum();
      AniListAnimeDetails firstPartDetails = detailsById.get(parts.get(0).anilistId());

      AnimeSeason season = new AnimeSeason();
      season.anime = anime;
      season.seasonNumber = seasonNumber;
      season.name = seasonNumber == 0 ? "Specials" : "Season " + seasonNumber;
      season.overview = firstPartDetails.overview();
      season.episodeCount = seasonEpisodeCount;
      season.anilistId = String.valueOf(parts.get(0).anilistId());
      season.absoluteOffset = seasonNumber == 0 ? 0 : offset;
      season.persist();

      int withinSeasonOffset = 0;
      for (FribbEntry part : parts) {
        AniListAnimeDetails partDetails = detailsById.get(part.anilistId());
        persistEpisodesForSeason(
            season, withinSeasonOffset, partDetails.episodeCount(), fribbEnrichmentFrom(part));
        withinSeasonOffset += partDetails.episodeCount();
      }

      if (seasonNumber != 0) {
        offset += seasonEpisodeCount;
      }
      total += seasonEpisodeCount;
      anyEpisodes = true;
    }
    return anyEpisodes ? total : null;
  }

  /**
   * Persists one cour's worth of episodes into {@code season}, numbered {@code withinSeasonOffset +
   * 1 .. withinSeasonOffset + episodeCount} within it — {@code withinSeasonOffset} is only ever
   * non-zero for a season's second (or later) part, see {@link #persistSeasons}. {@code
   * enrichment.episodes()} is matched in by this cour's own 1-based episode number and is optional
   * per-episode — a title missing from it (TMDB's season having fewer entries than AniList's count,
   * or no mapping at all) just falls back to the {@code "Episode N"} placeholder for that one
   * episode, same as before Fribb enrichment existed.
   *
   * <p>{@link TheXemMappingProvider} only ever corrects a cour's own within-cour position (it has
   * no concept of a multi-cour franchise), so its result — or {@code i} itself when there's no
   * correction — still needs {@code season.absoluteOffset + withinSeasonOffset} added on top to
   * land in the franchise's continuous count, which is what {@link AnimeReleaseParser}-driven
   * search/grab actually matches against.
   */
  private void persistEpisodesForSeason(
      AnimeSeason season, int withinSeasonOffset, int episodeCount, FribbEnrichment enrichment) {
    for (int i = 1; i <= episodeCount; i++) {
      int episodeNumber = i;
      TmdbShowStructure.EpisodeData tmdbEpisode =
          enrichment.episodes().stream()
              .filter(e -> e.episodeNumber() == episodeNumber)
              .findFirst()
              .orElse(null);

      MediaItem episodeMediaItem = new MediaItem();
      episodeMediaItem.contentType = "anime_episode";
      episodeMediaItem.title =
          tmdbEpisode != null && tmdbEpisode.title() != null ? tmdbEpisode.title() : "Episode " + i;
      episodeMediaItem.year = season.anime.mediaItem.year;
      episodeMediaItem.addedAt = Instant.now();
      episodeMediaItem.rootFolder = season.anime.mediaItem.rootFolder;
      episodeMediaItem.persist();

      int withinCourNumber =
          enrichment.anidbId() == null
              ? i
              : theXemMappingProvider
                  .sceneAbsoluteForAnidbEpisode(enrichment.anidbId(), i)
                  .orElse(i);

      AnimeEpisode episode = new AnimeEpisode();
      episode.mediaItem = episodeMediaItem;
      episode.season = season;
      episode.episodeNumber = withinSeasonOffset + i;
      episode.absoluteEpisodeNumber = season.absoluteOffset + withinSeasonOffset + withinCourNumber;
      episode.episodeType = "EPISODE";
      if (tmdbEpisode != null) {
        episode.overview = tmdbEpisode.overview();
        episode.airDate = tmdbEpisode.airDate();
        episode.runtimeMinutes = tmdbEpisode.runtimeMinutes();
        episode.stillPath = tmdbEpisode.stillPath();
      }
      episode.persist();
    }
  }
}

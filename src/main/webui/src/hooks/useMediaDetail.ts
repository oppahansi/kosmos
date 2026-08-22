import { useParams } from "react-router-dom";
import { api } from "../api/client";
import { useApi } from "./useApi";
import { useLibraryChanges } from "./useLibraryChanges";
import type {
  Anime,
  AnimeDetail,
  MediaDetailExtras,
  MediaPreview,
  MinimumAvailability,
  Movie,
  RefreshScanResult,
  SeriesMonitoringMode,
  Show,
  ShowDetail,
} from "../api/types";

export type MediaKind = "movie" | "show" | "anime";

/** The union every owned {@code Movie}/{@code ShowDetail}/{@code AnimeDetail} row shares. */
export type OwnedMedia = Movie | ShowDetail | AnimeDetail;

const MEDIA_TYPE_FOR_KIND: Record<MediaKind, "movie" | "tv" | "anime"> = {
  movie: "movie",
  show: "tv",
  anime: "anime",
};

/** The episode-level content type {@code LibraryChangeEvent} uses for a given show/anime kind. */
const EPISODE_CONTENT_TYPE: Partial<Record<MediaKind, string>> = {
  show: "episode",
  anime: "anime_episode",
};

/** {@code AddableItem.mediaType}/{@code CreateXRequest.mediaType} spelling for a given {@link MediaKind}. */
export function mediaTypeFor(kind: MediaKind): "movie" | "tv" | "anime" {
  return MEDIA_TYPE_FOR_KIND[kind];
}

const OWNED_PATH_PREFIX: Record<MediaKind, string> = {
  movie: "/movies",
  show: "/shows",
  anime: "/anime",
};

/** The owned detail route for a given {@link MediaKind} + id — {@code /movies/{id}} and so on. */
export function ownedPathFor(kind: MediaKind, id: string): string {
  return `${OWNED_PATH_PREFIX[kind]}/${id}`;
}

function fetchOwned(kind: MediaKind, id: string): Promise<OwnedMedia> {
  if (kind === "movie") return api.getMovie(id);
  if (kind === "show") return api.getShow(id);
  return api.getAnime(id);
}

function fetchExtras(kind: MediaKind, id: string): Promise<MediaDetailExtras> {
  if (kind === "movie") return api.getMovieDetailExtras(id);
  if (kind === "show") return api.getShowDetailExtras(id);
  return api.getAnimeDetailExtras(id);
}

function fetchPreview(kind: MediaKind, externalId: string): Promise<MediaPreview> {
  if (kind === "movie") return api.getMoviePreview(externalId);
  if (kind === "show") return api.getShowPreview(externalId);
  return api.getAnimePreview(externalId);
}

function saveQualityProfile(kind: MediaKind, id: string, qualityProfileId: string | null): Promise<Movie | Show | Anime> {
  if (kind === "movie") return api.updateMovieQualityProfile(id, qualityProfileId);
  if (kind === "show") return api.updateShowQualityProfile(id, qualityProfileId);
  return api.updateAnimeQualityProfile(id, qualityProfileId);
}

/** Anime has no root-folder reassignment yet — see the roadmap's own scope note on why. */
function saveRootFolder(kind: MediaKind, id: string, rootFolderId: string): Promise<Movie | ShowDetail> {
  return kind === "movie" ? api.updateMovieRootFolder(id, rootFolderId) : api.updateShowRootFolder(id, rootFolderId);
}

function saveEpisodeMonitored(kind: MediaKind, episodeId: string, monitored: boolean): Promise<unknown> {
  return kind === "anime"
    ? api.updateAnimeEpisodeMonitored(episodeId, monitored)
    : api.updateEpisodeMonitored(episodeId, monitored);
}

function saveSeriesMonitoring(
  kind: MediaKind,
  id: string,
  mode: SeriesMonitoringMode,
  seasonNumber?: number,
): Promise<unknown> {
  return kind === "anime"
    ? api.updateAnimeMonitoring(id, mode, seasonNumber)
    : api.updateShowMonitoring(id, mode, seasonNumber);
}

/**
 * All the data a media detail page needs, for whichever of the three real content types the route
 * is for — one owned/preview/extras fetch dispatch instead of three near-identical page components.
 * {@code kind} is a stable prop for the lifetime of one mount (each of the six detail routes maps to
 * a distinct {@code <Route>}, so React remounts rather than reusing an instance across kinds), so
 * dispatching on it here doesn't risk a conditional hook call.
 */
export function useMediaDetail(kind: MediaKind) {
  const { id, externalId } = useParams<{ id?: string; externalId?: string }>();
  const owned = !!id;

  const { data: ownedMedia, loading: ownedLoading, error: ownedError, reload } = useApi(
    () => (id ? fetchOwned(kind, id) : Promise.resolve(null)),
    [kind, id],
  );
  const { data: extras } = useApi(() => (id ? fetchExtras(kind, id) : Promise.resolve(null)), [kind, id]);
  const { data: libraryFiles, reload: reloadLibraryFiles } = useApi(
    () => (kind === "movie" && id ? api.listMovieLibraryFiles(id) : Promise.resolve([])),
    [kind, id],
  );
  const { data: profiles } = useApi(() => api.listQualityProfiles(), []);
  const { data: rootFolders } = useApi(() => (kind === "anime" ? Promise.resolve([]) : api.listRootFolders()), [kind]);
  const {
    data: preview,
    loading: previewLoading,
    error: previewError,
  } = useApi(() => (externalId ? fetchPreview(kind, externalId) : Promise.resolve(null)), [kind, externalId]);

  // Live updates (see LibraryChangeBroadcaster): this title's own kind always reloads it; the
  // episode-level content type only reloads when a changed episode id actually belongs to the
  // season/episode tree already loaded here — an event only carries an id, not "which show," so
  // this is what tells an unrelated show's newly-imported episode apart from this one's.
  const episodeContentType = EPISODE_CONTENT_TYPE[kind];
  useLibraryChanges(
    episodeContentType ? [kind, episodeContentType] : [kind],
    (ids) => {
      if (!id) return;
      if (ids.includes(id)) {
        reload();
        if (kind === "movie") reloadLibraryFiles();
        return;
      }
      const seasons = (ownedMedia as ShowDetail | AnimeDetail | null)?.seasons;
      if (seasons?.some((season) => season.episodes.some((episode) => ids.includes(episode.id)))) {
        reload();
      }
    },
  );

  async function setQualityProfile(qualityProfileId: string | null) {
    if (!id) return;
    await saveQualityProfile(kind, id, qualityProfileId);
    reload();
  }

  async function setRootFolder(rootFolderId: string) {
    if (!id || kind === "anime") return;
    await saveRootFolder(kind, id, rootFolderId);
    reload();
  }

  async function setMinimumAvailability(minimumAvailability: MinimumAvailability) {
    if (!id || kind !== "movie") return;
    await api.updateMinimumAvailability(id, minimumAvailability);
    reload();
  }

  async function setSeasonFolderEnabled(seasonFolderEnabled: boolean | null) {
    if (!id || kind !== "show") return;
    await api.updateShowSeasonFolder(id, seasonFolderEnabled);
    reload();
  }

  async function setEpisodeMonitored(episodeId: string, monitored: boolean) {
    if (kind === "movie") return;
    await saveEpisodeMonitored(kind, episodeId, monitored);
    reload();
  }

  async function setSeriesMonitoring(mode: SeriesMonitoringMode, seasonNumber?: number) {
    if (!id || kind === "movie") return;
    await saveSeriesMonitoring(kind, id, mode, seasonNumber);
    reload();
  }

  async function refreshMedia(): Promise<RefreshScanResult | null> {
    if (!id) return null;
    const result =
      kind === "movie" ? await api.refreshMovie(id) : kind === "show" ? await api.refreshShow(id) : await api.refreshAnime(id);
    reload();
    if (kind === "movie") reloadLibraryFiles();
    return result;
  }

  return {
    owned,
    ownedMedia,
    ownedLoading,
    ownedError,
    extras,
    libraryFiles: libraryFiles ?? [],
    reloadLibraryFiles,
    profiles,
    rootFolders: rootFolders ?? [],
    preview,
    previewLoading,
    previewError,
    setQualityProfile,
    setRootFolder,
    setMinimumAvailability,
    setSeasonFolderEnabled,
    setEpisodeMonitored,
    setSeriesMonitoring,
    refreshMedia,
  };
}

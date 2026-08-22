import type {
  ActivityHistoryItem,
  ActivityStats,
  Anime,
  AnimeDetail,
  AnimeEpisodeDetail,
  BackupFile,
  BecauseYouAddedResult,
  BlocklistEntry,
  CalendarEntry,
  CustomFormat,
  DiscoverItem,
  DownloadClient,
  EpisodeDetail,
  GenreTile,
  Grab,
  HealthCheckEntry,
  ImportFromProwlarrResult,
  Indexer,
  CommitImportResult,
  ImportCandidate,
  ImportList,
  ImportListExclusion,
  MovieCollection,
  MovieCollectionDetail,
  SeasonPassEntry,
  JellyfinLibrary,
  JellyfinServer,
  JellyfinUser,
  JobRun,
  LibraryFile,
  BrowseResult,
  LibraryContentType,
  LibraryRootFolder,
  LibraryStats,
  MediaDetailExtras,
  MediaPreview,
  MediaRequest,
  MinimumAvailability,
  MetadataSearchResult,
  MetadataStatus,
  NamingSettings,
  TmdbTestResult,
  Movie,
  NotificationEventType,
  Notifier,
  PluginManifest,
  PreviewRenameResult,
  QualityDefinition,
  QualityProfile,
  RadarrServer,
  RefreshScanResult,
  RegistryEntry,
  ScheduledJob,
  ScoredSearchResult,
  SeriesMonitoringMode,
  SetupStatus,
  Show,
  ShowDetail,
  SonarrServer,
  StudioTile,
  TestDownloadClientResult,
  TestIndexerResult,
  TrashImportResult,
  UnclassifiedShow,
  User,
} from "./types";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

// REST resources are mounted under /api (quarkus.rest.path) precisely so a full-page reload on
// a frontend detail route like /anime/:id never collides with the backend's own @Path("/anime/{id}")
// and gets served raw JSON instead of the SPA shell. Call sites below stay written as the bare
// resource path ("/movies", "/anime/{id}") — this is the one place that adds the prefix.
const API_BASE = "/api";

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(API_BASE + path, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new ApiError(response.status, body || `${init?.method ?? "GET"} ${path} failed: ${response.status}`);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

// No Content-Type header here on purpose — fetch sets the multipart boundary itself from the
// FormData body, and forcing application/json (like request() does) would break the upload.
async function requestMultipart<T>(path: string, formData: FormData): Promise<T> {
  const response = await fetch(API_BASE + path, { method: "POST", body: formData });
  if (!response.ok) {
    const body = await response.text().catch(() => "");
    throw new ApiError(response.status, body || `POST ${path} failed: ${response.status}`);
  }
  return response.json() as Promise<T>;
}

export const api = {
  listMovies: () => request<Movie[]>("/movies"),

  getMovie: (id: string) => request<Movie>(`/movies/${id}`),

  createMovie: (body: {
    externalId?: string;
    pluginSlug?: string;
    title: string;
    year: number | null;
    overview: string | null;
    posterPath: string | null;
    backdropPath: string | null;
  }) => request<Movie>("/movies", { method: "POST", body: JSON.stringify(body) }),

  updateMovieQualityProfile: (id: string, qualityProfileId: string | null) =>
    request<Movie>(`/movies/${id}/quality-profile`, {
      method: "PUT",
      body: JSON.stringify({ qualityProfileId }),
    }),

  updateMinimumAvailability: (id: string, minimumAvailability: MinimumAvailability) =>
    request<Movie>(`/movies/${id}/minimum-availability`, {
      method: "PUT",
      body: JSON.stringify({ minimumAvailability }),
    }),

  listMovieLibraryFiles: (id: string) => request<LibraryFile[]>(`/movies/${id}/library-files`),

  deleteLibraryFile: (id: string, deleteFromDisk: boolean) =>
    request<void>(`/library-files/${id}?deleteFromDisk=${deleteFromDisk}`, { method: "DELETE" }),

  rematchLibraryFile: (id: string, mediaItemId: string) =>
    request<LibraryFile>(`/library-files/${id}/media-item`, {
      method: "PUT",
      body: JSON.stringify({ mediaItemId }),
    }),

  previewRenameLibraryFile: (id: string) =>
    request<PreviewRenameResult>(`/library-files/${id}/preview-rename`),

  deleteMovie: (id: string, deleteFiles: boolean) =>
    request<void>(`/movies/${id}?deleteFiles=${deleteFiles}`, { method: "DELETE" }),

  refreshMovie: (id: string) => request<RefreshScanResult>(`/movies/${id}/refresh`, { method: "POST" }),

  getMovieDetailExtras: (id: string) => request<MediaDetailExtras>(`/movies/${id}/detail-extras`),

  getMoviePreview: (externalId: string) => request<MediaPreview>(`/movies/tmdb/${externalId}`),

  libraryStats: () => request<LibraryStats>("/library/stats"),

  listRootFolders: () => request<LibraryRootFolder[]>("/library/root-folders"),

  createRootFolder: (path: string, contentTypes: LibraryContentType[] = []) =>
    request<LibraryRootFolder>("/library/root-folders", {
      method: "POST",
      body: JSON.stringify({ path, contentTypes }),
    }),

  deleteRootFolder: (id: string) => request<void>(`/library/root-folders/${id}`, { method: "DELETE" }),

  listNamingSettings: () => request<NamingSettings[]>("/naming-settings"),

  updateNamingSettings: (
    contentType: string,
    body: { folderTemplate: string; fileTemplate: string; seasonFolderTemplate: string | null },
  ) =>
    request<NamingSettings>(`/naming-settings/${contentType}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  browseFilesystem: (path?: string) =>
    request<BrowseResult>(`/filesystem/browse${path ? `?path=${encodeURIComponent(path)}` : ""}`),

  listRequests: () => request<MediaRequest[]>("/requests"),

  createRequest: (body: {
    externalId: string;
    pluginSlug: string;
    mediaType: "movie" | "tv" | "anime";
    title: string;
    year: number | null;
    overview: string | null;
    posterPath: string | null;
    backdropPath: string | null;
    qualityProfileId?: string | null;
  }) => request<MediaRequest>("/requests", { method: "POST", body: JSON.stringify(body) }),

  approveRequest: (id: string, qualityProfileId?: string | null) =>
    request<MediaRequest>(`/requests/${id}/approve`, {
      method: "POST",
      body: JSON.stringify({ qualityProfileId: qualityProfileId ?? null }),
    }),

  declineRequest: (id: string, note?: string | null) =>
    request<MediaRequest>(`/requests/${id}/decline`, {
      method: "POST",
      body: JSON.stringify({ note: note ?? null }),
    }),

  searchMetadata: (query: string) =>
    request<MetadataSearchResult[]>(`/metadata/search?q=${encodeURIComponent(query)}`),

  metadataStatus: () => request<MetadataStatus>("/metadata/status"),

  testTmdb: () => request<TmdbTestResult>("/metadata/tmdb/test", { method: "POST" }),

  listInstalledPlugins: () => request<PluginManifest[]>("/plugins"),

  listRegistryPlugins: () => request<RegistryEntry[]>("/plugins/registry"),

  discoverRecent: () => request<DiscoverItem[]>("/discover/recent"),

  discoverTrending: (
    window: "day" | "week" = "week",
    mediaType: "all" | "movie" | "tv" = "all",
    page = 1,
    excludeLanguages: string[] = [],
  ) =>
    request<DiscoverItem[]>(
      `/discover/trending?window=${window}&mediaType=${mediaType}&page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  discoverPopular: (page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(`/discover/popular?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`),

  discoverBecauseYouAdded: () =>
    request<BecauseYouAddedResult | undefined>("/discover/because-you-added"),

  discoverUpcomingMovies: (page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(
      `/discover/upcoming-movies?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  discoverPopularTv: (page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(`/discover/popular-tv?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`),

  discoverUpcomingTv: (page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(`/discover/upcoming-tv?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`),

  discoverMovieGenres: () => request<GenreTile[]>("/discover/genres/movie"),

  discoverTvGenres: () => request<GenreTile[]>("/discover/genres/tv"),

  discoverMoviesByGenre: (id: number, page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(
      `/discover/genre/movie/${id}?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  discoverTvByGenre: (id: number, page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(
      `/discover/genre/tv/${id}?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  discoverStudios: () => request<StudioTile[]>("/discover/studios"),

  discoverNetworks: () => request<StudioTile[]>("/discover/networks"),

  discoverMoviesByStudio: (id: number, page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(
      `/discover/studio/${id}?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  discoverTvByNetwork: (id: number, page = 1, excludeLanguages: string[] = []) =>
    request<DiscoverItem[]>(
      `/discover/network/${id}?page=${page}&excludeLanguages=${excludeLanguages.join(",")}`,
    ),

  listShows: () => request<Show[]>("/shows"),

  getShow: (id: string) => request<ShowDetail>(`/shows/${id}`),

  getShowDetailExtras: (id: string) => request<MediaDetailExtras>(`/shows/${id}/detail-extras`),

  getShowPreview: (externalId: string) => request<MediaPreview>(`/shows/tmdb/${externalId}`),

  updateShowQualityProfile: (id: string, qualityProfileId: string | null) =>
    request<ShowDetail>(`/shows/${id}/quality-profile`, {
      method: "PUT",
      body: JSON.stringify({ qualityProfileId }),
    }),

  updateShowSeasonFolder: (id: string, seasonFolderEnabled: boolean | null) =>
    request<ShowDetail>(`/shows/${id}/season-folder`, {
      method: "PUT",
      body: JSON.stringify({ seasonFolderEnabled }),
    }),

  updateShowMonitoring: (id: string, mode: SeriesMonitoringMode, seasonNumber?: number) =>
    request<ShowDetail>(`/shows/${id}/monitoring`, {
      method: "PUT",
      body: JSON.stringify({ mode, seasonNumber: seasonNumber ?? null }),
    }),

  deleteShow: (id: string, deleteFiles: boolean) =>
    request<void>(`/shows/${id}?deleteFiles=${deleteFiles}`, { method: "DELETE" }),

  refreshShow: (id: string) => request<RefreshScanResult>(`/shows/${id}/refresh`, { method: "POST" }),

  getSeasonPass: () => request<SeasonPassEntry[]>("/season-pass"),

  createShow: (body: {
    externalId?: string;
    pluginSlug?: string;
    title: string;
    year: number | null;
    overview: string | null;
    posterPath: string | null;
    backdropPath: string | null;
  }) => request<Show>("/shows", { method: "POST", body: JSON.stringify(body) }),

  getEpisode: (id: string) => request<EpisodeDetail>(`/episodes/${id}`),

  updateEpisodeMonitored: (id: string, monitored: boolean) =>
    request<EpisodeDetail>(`/episodes/${id}/monitored`, {
      method: "PUT",
      body: JSON.stringify({ monitored }),
    }),

  getAnimeEpisode: (id: string) => request<AnimeEpisodeDetail>(`/anime-episodes/${id}`),

  updateAnimeEpisodeMonitored: (id: string, monitored: boolean) =>
    request<AnimeEpisodeDetail>(`/anime-episodes/${id}/monitored`, {
      method: "PUT",
      body: JSON.stringify({ monitored }),
    }),

  listAnime: () => request<Anime[]>("/anime"),

  getAnime: (id: string) => request<AnimeDetail>(`/anime/${id}`),

  getAnimeDetailExtras: (id: string) => request<MediaDetailExtras>(`/anime/${id}/detail-extras`),

  getAnimePreview: (externalId: string) => request<MediaPreview>(`/anime/anilist/${externalId}`),

  updateAnimeQualityProfile: (id: string, qualityProfileId: string | null) =>
    request<AnimeDetail>(`/anime/${id}/quality-profile`, {
      method: "PUT",
      body: JSON.stringify({ qualityProfileId }),
    }),

  updateAnimeMonitoring: (id: string, mode: SeriesMonitoringMode, seasonNumber?: number) =>
    request<AnimeDetail>(`/anime/${id}/monitoring`, {
      method: "PUT",
      body: JSON.stringify({ mode, seasonNumber: seasonNumber ?? null }),
    }),

  deleteAnime: (id: string, deleteFiles: boolean) =>
    request<void>(`/anime/${id}?deleteFiles=${deleteFiles}`, { method: "DELETE" }),

  refreshAnime: (id: string) => request<RefreshScanResult>(`/anime/${id}/refresh`, { method: "POST" }),

  createAnime: (body: {
    externalId?: string;
    pluginSlug?: string;
    title: string;
    year: number | null;
    overview: string | null;
    posterPath: string | null;
    backdropPath: string | null;
  }) => request<Anime>("/anime", { method: "POST", body: JSON.stringify(body) }),

  listIndexers: () => request<Indexer[]>("/indexers"),

  createIndexer: (body: { name: string; baseUrl: string; apiKey: string }) =>
    request<Indexer>("/indexers", { method: "POST", body: JSON.stringify(body) }),

  importIndexersFromProwlarr: (body: { baseUrl: string; apiKey: string }) =>
    request<ImportFromProwlarrResult>("/indexers/import-from-prowlarr", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  testIndexer: (body: { baseUrl: string; apiKey: string }) =>
    request<TestIndexerResult>("/indexers/test", { method: "POST", body: JSON.stringify(body) }),

  testProwlarrConnection: (body: { baseUrl: string; apiKey: string }) =>
    request<TestIndexerResult>("/indexers/test-prowlarr", { method: "POST", body: JSON.stringify(body) }),

  searchIndexer: (
    indexerId: string,
    query: string,
    qualityProfileId?: string,
    runtimeMinutes?: number | null,
    mediaItemId?: string,
  ) =>
    request<ScoredSearchResult[]>(
      `/indexers/${indexerId}/search?q=${encodeURIComponent(query)}${qualityProfileId ? `&qualityProfileId=${qualityProfileId}` : ""}${runtimeMinutes ? `&runtimeMinutes=${runtimeMinutes}` : ""}${mediaItemId ? `&mediaItemId=${mediaItemId}` : ""}`,
    ),

  searchIndexerScored: (
    indexerId: string,
    query: string,
    qualityProfileId: string,
    runtimeMinutes?: number | null,
    mediaItemId?: string,
  ) =>
    request<ScoredSearchResult[]>(
      `/indexers/${indexerId}/search?q=${encodeURIComponent(query)}&qualityProfileId=${qualityProfileId}${runtimeMinutes ? `&runtimeMinutes=${runtimeMinutes}` : ""}${mediaItemId ? `&mediaItemId=${mediaItemId}` : ""}`,
    ),

  listQualityProfiles: () => request<QualityProfile[]>("/quality-profiles"),

  createQualityProfile: (body: {
    name: string;
    cutoffScore: number;
    customFormatIds: string[];
    grabDelayMinutes?: number;
    bypassScore?: number | null;
  }) => request<QualityProfile>("/quality-profiles", { method: "POST", body: JSON.stringify(body) }),

  updateQualityProfile: (
    id: string,
    body: {
      name: string;
      cutoffScore: number;
      customFormatIds: string[];
      grabDelayMinutes: number;
      bypassScore: number | null;
    },
  ) => request<QualityProfile>(`/quality-profiles/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  deleteQualityProfile: (id: string) => request<void>(`/quality-profiles/${id}`, { method: "DELETE" }),

  listCustomFormats: () => request<CustomFormat[]>("/custom-formats"),

  createCustomFormat: (body: { name: string; score: number; rule: string }) =>
    request<CustomFormat>("/custom-formats", { method: "POST", body: JSON.stringify(body) }),

  updateCustomFormat: (id: string, body: { name: string; score: number; rule: string }) =>
    request<CustomFormat>(`/custom-formats/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  deleteCustomFormat: (id: string) => request<void>(`/custom-formats/${id}`, { method: "DELETE" }),

  importTrashGuides: () =>
    request<TrashImportResult>("/custom-formats/import/trash-guides", { method: "POST" }),

  listQualityDefinitions: () => request<QualityDefinition[]>("/quality-definitions"),

  createQualityDefinition: (body: { resolution: string; source: string; minMbPerMinute: number; maxMbPerMinute: number }) =>
    request<QualityDefinition>("/quality-definitions", { method: "POST", body: JSON.stringify(body) }),

  updateQualityDefinition: (
    id: string,
    body: { resolution: string; source: string; minMbPerMinute: number; maxMbPerMinute: number },
  ) => request<QualityDefinition>(`/quality-definitions/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  deleteQualityDefinition: (id: string) => request<void>(`/quality-definitions/${id}`, { method: "DELETE" }),

  importQualityDefinitionsTrashGuides: () =>
    request<TrashImportResult>("/quality-definitions/import/trash-guides", { method: "POST" }),

  listDownloadClients: () => request<DownloadClient[]>("/download-clients"),

  createDownloadClient: (body: {
    name: string;
    type?: string;
    baseUrl: string;
    username: string | null;
    password: string | null;
    category: string | null;
  }) => request<DownloadClient>("/download-clients", { method: "POST", body: JSON.stringify(body) }),

  testDownloadClient: (body: { type: string; baseUrl: string; username: string | null; password: string | null }) =>
    request<TestDownloadClientResult>("/download-clients/test", { method: "POST", body: JSON.stringify(body) }),

  updateDownloadClientPathMapping: (id: string, body: { remotePath: string | null; localPath: string | null }) =>
    request<DownloadClient>(`/download-clients/${id}/path-mapping`, { method: "PUT", body: JSON.stringify(body) }),

  listSystemChecks: () => request<HealthCheckEntry[]>("/system-checks"),

  listBackups: () => request<BackupFile[]>("/backups"),

  deleteBackup: (filename: string) =>
    request<void>(`/backups/${encodeURIComponent(filename)}`, { method: "DELETE" }),

  calendar: (from: string, to: string, monitoredOnly: boolean) =>
    request<CalendarEntry[]>(`/calendar?from=${from}&to=${to}&monitoredOnly=${monitoredOnly}`),

  listImportLists: () => request<ImportList[]>("/import-lists"),

  createImportList: (body: { name: string; sourceType: string; trusted: boolean; qualityProfileId: string | null }) =>
    request<ImportList>("/import-lists", { method: "POST", body: JSON.stringify(body) }),

  updateImportList: (
    id: string,
    body: { name: string; enabled: boolean; trusted: boolean; qualityProfileId: string | null },
  ) => request<ImportList>(`/import-lists/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  deleteImportList: (id: string) => request<void>(`/import-lists/${id}`, { method: "DELETE" }),

  listImportListExclusions: () => request<ImportListExclusion[]>("/import-lists/exclusions"),

  excludeFromImportLists: (body: { pluginSlug: string; externalId: string; title: string }) =>
    request<void>("/import-lists/exclusions", { method: "POST", body: JSON.stringify(body) }),

  listMovieCollections: () => request<MovieCollection[]>("/movie-collections"),

  getMovieCollection: (tmdbCollectionId: string) =>
    request<MovieCollectionDetail>(`/movie-collections/tmdb/${tmdbCollectionId}`),

  monitorMovieCollection: (tmdbCollectionId: string, qualityProfileId: string | null) =>
    request<MovieCollection>(`/movie-collections/tmdb/${tmdbCollectionId}/monitor`, {
      method: "POST",
      body: JSON.stringify({ qualityProfileId }),
    }),

  unmonitorMovieCollection: (id: string) =>
    request<void>(`/movie-collections/${id}/monitor`, { method: "DELETE" }),

  removeImportListExclusion: (id: string) =>
    request<void>(`/import-lists/exclusions/${id}`, { method: "DELETE" }),

  grabRelease: (
    movieId: string,
    body: {
      title: string;
      downloadUrl: string;
      resolution: string | null;
      source: string | null;
      videoCodec: string | null;
      score: number | null;
      downloadClientId: string;
    },
  ) => request<Grab>(`/movies/${movieId}/grab`, { method: "POST", body: JSON.stringify(body) }),

  grabFile: (movieId: string, file: File, title: string, downloadClientId: string) => {
    const formData = new FormData();
    formData.append("file", file);
    formData.append("title", title);
    formData.append("downloadClientId", downloadClientId);
    return requestMultipart<Grab>(`/movies/${movieId}/grab/file`, formData);
  },

  grabEpisodeRelease: (
    episodeId: string,
    body: {
      title: string;
      downloadUrl: string;
      resolution: string | null;
      source: string | null;
      videoCodec: string | null;
      score: number | null;
      downloadClientId: string;
    },
  ) => request<Grab>(`/episodes/${episodeId}/grab`, { method: "POST", body: JSON.stringify(body) }),

  grabEpisodeFile: (episodeId: string, file: File, title: string, downloadClientId: string) => {
    const formData = new FormData();
    formData.append("file", file);
    formData.append("title", title);
    formData.append("downloadClientId", downloadClientId);
    return requestMultipart<Grab>(`/episodes/${episodeId}/grab/file`, formData);
  },

  listGrabs: () => request<Grab[]>("/grabs"),

  markGrabFailed: (grabId: string) => request<Grab>(`/grabs/${grabId}/mark-failed`, { method: "POST" }),

  listActivityHistory: (limit?: number) =>
    request<ActivityHistoryItem[]>(`/activity/history${limit ? `?limit=${limit}` : ""}`),

  activityStats: () => request<ActivityStats>("/activity/stats"),

  listBlocklist: () => request<BlocklistEntry[]>("/blocklist"),

  removeBlocklistEntry: (id: string) => request<void>(`/blocklist/${id}`, { method: "DELETE" }),

  importPath: (movieId: string, sourcePath: string) =>
    request<LibraryFile>(`/movies/${movieId}/import`, {
      method: "POST",
      body: JSON.stringify({ sourcePath }),
    }),

  scanImportPath: (sourcePath: string) =>
    request<ImportCandidate[]>("/import/scan", { method: "POST", body: JSON.stringify({ sourcePath }) }),

  commitImport: (items: { sourcePath: string; mediaItemId: string }[]) =>
    request<CommitImportResult[]>("/import/commit", { method: "POST", body: JSON.stringify({ items }) }),

  probeLibraryFile: (id: string) => request<LibraryFile>(`/library-files/${id}/probe`, { method: "POST" }),

  listNotifiers: () => request<Notifier[]>("/notifiers"),

  createNotifier: (body: {
    name: string;
    type: string;
    url: string | null;
    token: string | null;
    target: string | null;
    enabledEvents: NotificationEventType[];
  }) => request<Notifier>("/notifiers", { method: "POST", body: JSON.stringify(body) }),

  listJellyfinServers: () => request<JellyfinServer[]>("/jellyfin-servers"),

  createJellyfinServer: (body: { name: string; baseUrl: string; apiKey: string }) =>
    request<JellyfinServer>("/jellyfin-servers", { method: "POST", body: JSON.stringify(body) }),

  testJellyfinConnection: (body: { baseUrl: string; apiKey: string }) =>
    request<{ ok: boolean; message: string; libraryCount: number }>("/jellyfin-servers/test-connection", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  syncJellyfinLibraries: (id: string) =>
    request<JobRun>(`/jellyfin-servers/${id}/sync-libraries`, { method: "POST" }),

  syncJellyfinUsers: (id: string) =>
    request<JobRun>(`/jellyfin-servers/${id}/sync-users`, { method: "POST" }),

  listJellyfinLibraries: (id: string) => request<JellyfinLibrary[]>(`/jellyfin-servers/${id}/libraries`),

  updateJellyfinLibrarySelection: (id: string, libraryIds: string[]) =>
    request<void>(`/jellyfin-servers/${id}/libraries`, { method: "PUT", body: JSON.stringify({ libraryIds }) }),

  listJellyfinUsers: (id: string) => request<JellyfinUser[]>(`/jellyfin-servers/${id}/users`),

  updateJellyfinUserSelection: (id: string, userIds: string[]) =>
    request<void>(`/jellyfin-servers/${id}/users`, { method: "PUT", body: JSON.stringify({ userIds }) }),

  autoRegisterRootFoldersFromJellyfin: (id: string) =>
    request<{ registered: number; skipped: number }>(`/jellyfin-servers/${id}/root-folders`, { method: "POST" }),

  listRadarrServers: () => request<RadarrServer[]>("/radarr-servers"),

  createRadarrServer: (body: { name: string; baseUrl: string; apiKey: string }) =>
    request<RadarrServer>("/radarr-servers", { method: "POST", body: JSON.stringify(body) }),

  testRadarrConnection: (body: { baseUrl: string; apiKey: string }) =>
    request<{ ok: boolean; message: string }>("/radarr-servers/test-connection", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  syncRadarrLibrary: (id: string) => request<JobRun>(`/radarr-servers/${id}/sync-library`, { method: "POST" }),

  autoRegisterRootFoldersFromRadarr: (id: string) =>
    request<{ registered: number; skipped: number }>(`/radarr-servers/${id}/root-folders`, { method: "POST" }),

  listSonarrServers: () => request<SonarrServer[]>("/sonarr-servers"),

  createSonarrServer: (body: { name: string; baseUrl: string; apiKey: string }) =>
    request<SonarrServer>("/sonarr-servers", { method: "POST", body: JSON.stringify(body) }),

  testSonarrConnection: (body: { baseUrl: string; apiKey: string }) =>
    request<{ ok: boolean; message: string }>("/sonarr-servers/test-connection", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  syncSonarrLibrary: (id: string) => request<JobRun>(`/sonarr-servers/${id}/sync-library`, { method: "POST" }),

  autoRegisterRootFoldersFromSonarr: (id: string) =>
    request<{ registered: number; skipped: number }>(`/sonarr-servers/${id}/root-folders`, { method: "POST" }),

  listUnclassifiedShows: () => request<UnclassifiedShow[]>("/unclassified-shows"),

  resolveUnclassifiedAsShow: (id: string) =>
    request<void>(`/unclassified-shows/${id}/resolve-as-show`, { method: "POST" }),

  resolveUnclassifiedAsAnime: (id: string, anilistId: string) =>
    request<void>(`/unclassified-shows/${id}/resolve-as-anime`, {
      method: "POST",
      body: JSON.stringify({ anilistId }),
    }),

  dismissUnclassified: (id: string) => request<void>(`/unclassified-shows/${id}`, { method: "DELETE" }),

  listJobs: () => request<ScheduledJob[]>("/jobs"),

  listJobRuns: (name: string, limit = 20) =>
    request<JobRun[]>(`/jobs/${encodeURIComponent(name)}/runs?limit=${limit}`),

  runJobNow: (name: string) => request<JobRun>(`/jobs/${encodeURIComponent(name)}/run`, { method: "POST" }),

  updateJob: (name: string, body: { enabled: boolean; intervalSeconds: number }) =>
    request<ScheduledJob>(`/jobs/${encodeURIComponent(name)}`, { method: "PUT", body: JSON.stringify(body) }),

  setupStatus: () => request<SetupStatus>("/auth/setup-status"),

  bootstrapJellyfin: (body: { serverUrl: string; username: string; password: string }) =>
    request<{ serverId: string }>("/auth/bootstrap/jellyfin", { method: "POST", body: JSON.stringify(body) }),

  login: (username: string, password: string) =>
    request<User>("/auth/login", { method: "POST", body: JSON.stringify({ username, password }) }),

  logout: () => request<void>("/auth/logout", { method: "POST" }),

  me: () => request<User>("/auth/me"),

  listUsers: () => request<User[]>("/users"),

  createUser: (body: { username: string; displayName: string; password: string; role?: string }) =>
    request<User>("/users", { method: "POST", body: JSON.stringify(body) }),
};

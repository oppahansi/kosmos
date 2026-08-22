export type MinimumAvailability = "ANNOUNCED" | "IN_CINEMAS" | "RELEASED";

export interface Movie {
  id: string;
  title: string;
  year: number | null;
  runtimeMinutes: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  addedAt: string;
  qualityProfileId: string | null;
  releaseDate: string | null;
  digitalReleaseDate: string | null;
  minimumAvailability: MinimumAvailability;
  rootFolderId: string | null;
}

export interface Show {
  id: string;
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  status: string | null;
  addedAt: string;
  qualityProfileId: string | null;
  partiallyAvailable: boolean;
  rootFolderId: string | null;
}

export type EpisodeStatus = "MISSING" | "GRABBED" | "FAILED" | "IMPORTED" | "AVAILABLE";

export type SeriesMonitoringMode = "ALL" | "NONE" | "FUTURE" | "MISSING";

export interface Episode {
  id: string;
  episodeNumber: number;
  title: string;
  overview: string | null;
  airDate: string | null;
  runtimeMinutes: number | null;
  stillPath: string | null;
  monitored: boolean;
  status: EpisodeStatus;
}

export interface EpisodeDetail {
  id: string;
  episodeNumber: number;
  title: string;
  runtimeMinutes: number | null;
  showId: string;
  showTitle: string;
  seasonNumber: number;
  qualityProfileId: string | null;
  status: EpisodeStatus;
}

export interface Season {
  id: string;
  seasonNumber: number;
  name: string;
  overview: string | null;
  posterPath: string | null;
  episodeCount: number | null;
  episodes: Episode[];
}

export interface ShowDetail extends Show {
  seasonFolderEnabled: boolean | null;
  seasons: Season[];
}

export interface Anime {
  id: string;
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  status: string | null;
  episodeCountTotal: number | null;
  addedAt: string;
  qualityProfileId: string | null;
  partiallyAvailable: boolean;
}

export interface AnimeEpisode {
  id: string;
  episodeNumber: number | null;
  absoluteEpisodeNumber: number | null;
  episodeType: string;
  title: string;
  overview: string | null;
  airDate: string | null;
  runtimeMinutes: number | null;
  stillPath: string | null;
  monitored: boolean;
  status: EpisodeStatus;
}

/** One AniList cour of an anime franchise — see the backend's AnimeSeason for how these are found. */
export interface AnimeSeason {
  id: string;
  seasonNumber: number;
  name: string;
  overview: string | null;
  episodeCount: number | null;
  episodes: AnimeEpisode[];
}

export interface AnimeDetail extends Anime {
  seasons: AnimeSeason[];
}

export interface AnimeEpisodeDetail {
  id: string;
  episodeNumber: number | null;
  absoluteEpisodeNumber: number | null;
  title: string;
  runtimeMinutes: number | null;
  animeId: string;
  animeTitle: string;
  qualityProfileId: string | null;
  status: EpisodeStatus;
}

export interface PluginManifest {
  slug: string;
  name: string;
  version: string;
  kind: string;
  entryPoint: string;
  permissions: {
    allowedHosts: string[];
  };
}

export interface RegistryEntry {
  slug: string;
  name: string;
  description: string;
  category: "Metadata" | "Artwork" | "Subtitles" | "Sync";
  publisher: string;
  repository: string;
  version: string;
  checksum: string;
  homepage: string | null;
}

export interface MetadataSearchResult {
  mediaItemId: string | null;
  externalId: string;
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  voteAverage: number | null;
  mediaType: "movie" | "tv" | "anime";
  /** Only ever populated for anime results (AniList's own episode total). */
  episodeCount: number | null;
  inLibrary: boolean;
  partiallyAvailable: boolean;
}

export interface DetailFact {
  k: string;
  v: string;
}

export interface CastMember {
  name: string;
  role: string;
  profilePath: string | null;
}

export interface MovieCollectionInfo {
  externalId: string;
  name: string;
  posterPath: string | null;
  backdropPath: string | null;
}

export interface MediaDetailExtras {
  genres: string[];
  facts: DetailFact[];
  voteAverage: number | null;
  voteCount: number | null;
  certification: string | null;
  cast: CastMember[];
  similar: MetadataSearchResult[];
  trailerUrl: string | null;
  collection: MovieCollectionInfo | null;
}

export interface MovieCollectionMember {
  externalId: string;
  title: string;
  year: number | null;
  posterPath: string | null;
  inLibrary: boolean;
  mediaItemId: string | null;
}

export interface MovieCollectionDetail {
  tmdbCollectionId: string;
  name: string;
  posterPath: string | null;
  backdropPath: string | null;
  monitored: boolean;
  movieCollectionId: string | null;
  members: MovieCollectionMember[];
}

export interface MovieCollection {
  id: string;
  tmdbCollectionId: string;
  name: string;
  posterPath: string | null;
  backdropPath: string | null;
  monitored: boolean;
  qualityProfileId: string | null;
  qualityProfileName: string | null;
  lastSyncedAt: string | null;
}

export interface PreviewEpisode {
  episodeNumber: number;
  absoluteEpisodeNumber: number | null;
  title: string;
  airDate: string | null;
}

export interface PreviewSeason {
  seasonNumber: number;
  name: string;
  episodeCount: number | null;
  episodes: PreviewEpisode[];
}

/** The detail screen for a title Kosmos doesn't own yet — see {@link MediaDetailExtras}. */
export interface MediaPreview {
  externalId: string;
  pluginSlug: string;
  mediaType: "movie" | "tv" | "anime";
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  genres: string[];
  facts: DetailFact[];
  voteAverage: number | null;
  voteCount: number | null;
  certification: string | null;
  cast: CastMember[];
  similar: MetadataSearchResult[];
  seasons: PreviewSeason[];
  episodes: PreviewEpisode[];
  trailerUrl: string | null;
}

export interface Indexer {
  id: string;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  enabled: boolean;
  createdAt: string;
}

export interface TorznabResult {
  title: string;
  downloadUrl: string;
  sizeBytes: number;
  seeders: number | null;
  peers: number | null;
  publishedAt: string | null;
}

export interface ParsedRelease {
  title: string;
  year: number | null;
  resolution: string | null;
  source: string | null;
  videoCodec: string | null;
  audioCodec: string | null;
  edition: string | null;
  releaseGroup: string | null;
  proper: boolean;
  repack: boolean;
}

export interface CustomFormatMatch {
  customFormatId: string;
  name: string;
  score: number;
  matched: boolean;
}

export interface ScoredSearchResult {
  raw: TorznabResult;
  parsed: ParsedRelease;
  score: number | null;
  cutoffScore: number | null;
  passesCutoff: boolean | null;
  formatBreakdown: CustomFormatMatch[] | null;
  sizeGateReason: string | null;
  /** Non-null means this exact release already failed for the title being searched for. */
  blocklistReason: string | null;
}

export interface QualityDefinition {
  id: string;
  resolution: string;
  source: string;
  minMbPerMinute: number;
  maxMbPerMinute: number;
}

export interface CustomFormat {
  id: string;
  name: string;
  score: number;
  rule: string;
  trashId: string | null;
}

export interface TrashImportResult {
  created: number;
  updated: number;
  skipped: string[];
}

export interface QualityProfile {
  id: string;
  name: string;
  cutoffScore: number;
  customFormats: CustomFormat[];
  grabDelayMinutes: number;
  bypassScore: number | null;
}

export interface DownloadClient {
  id: string;
  name: string;
  type: string;
  baseUrl: string;
  username: string | null;
  passwordSet: boolean;
  category: string | null;
  remotePath: string | null;
  localPath: string | null;
  enabled: boolean;
  createdAt: string;
}

export interface HealthCheckEntry {
  source: string;
  severity: "OK" | "WARNING" | "ERROR";
  message: string | null;
}

export interface BackupFile {
  filename: string;
  sizeBytes: number;
  createdAt: string;
}

export type ImportListSourceType =
  | "TMDB_POPULAR_MOVIES"
  | "TMDB_UPCOMING_MOVIES"
  | "TMDB_TRENDING_MOVIES"
  | "TMDB_POPULAR_TV"
  | "TMDB_UPCOMING_TV"
  | "TMDB_TRENDING_TV";

export interface ImportList {
  id: string;
  name: string;
  sourceType: ImportListSourceType;
  mediaType: "movie" | "tv";
  enabled: boolean;
  trusted: boolean;
  qualityProfileId: string | null;
  qualityProfileName: string | null;
  lastSyncedAt: string | null;
}

export interface ImportListExclusion {
  id: string;
  pluginSlug: string;
  externalId: string;
  title: string;
  excludedAt: string;
}

export interface SeasonPassSeason {
  seasonNumber: number;
  haveCount: number;
  totalCount: number;
}

export interface SeasonPassEntry {
  mediaItemId: string;
  title: string;
  posterPath: string | null;
  contentType: "show" | "anime";
  seasons: SeasonPassSeason[];
}

export interface CalendarEntry {
  mediaItemId: string;
  contentType: "movie" | "episode" | "anime_episode";
  title: string;
  date: string;
  monitored: boolean;
  seasonNumber: number | null;
  episodeNumber: number | null;
  posterPath: string | null;
}

export interface Grab {
  id: string;
  releaseId: string;
  title: string;
  downloadClientId: string;
  downloadClientName: string;
  status: string;
  grabbedAt: string;
  progressPercent: number | null;
}

export interface ActivityStats {
  importedToday: number;
  failedToday: number;
}

export interface ActivityHistoryItem {
  id: string;
  mediaItemId: string | null;
  title: string;
  kind: "GRABBED" | "IMPORTED" | "UPGRADED" | "FAILED" | "FILE_DELETED" | "RENAMED";
  message: string;
  sizeBytes: number | null;
  occurredAt: string;
}

export interface BlocklistEntry {
  id: string;
  mediaItemId: string;
  mediaItemTitle: string;
  titleRaw: string;
  reason: string;
  blockedAt: string;
}

export interface LibraryFile {
  id: string;
  mediaItemId: string;
  path: string;
  sizeBytes: number;
  matchMethod: string;
  matchConfidence: number;
  matchPinned: boolean;
  importedAt: string;
  verified: boolean;
  probedAt: string | null;
  embeddedTitle: string | null;
  container: string | null;
  videoCodec: string | null;
  resolutionWidth: number | null;
  resolutionHeight: number | null;
  durationSeconds: number | null;
  hdrFormat: string | null;
  bitDepth: number | null;
}

export interface ImportCandidate {
  sourcePath: string;
  sizeBytes: number;
  parsedTitle: string | null;
  seasonNumber: number | null;
  episodeNumber: number | null;
  absoluteEpisodeNumber: number | null;
  suggestedMediaItemId: string | null;
  suggestedMediaItemTitle: string | null;
  suggestedContentType: "movie" | "episode" | "anime_episode" | null;
  ambiguous: boolean;
}

export interface CommitImportResult {
  sourcePath: string;
  success: boolean;
  error: string | null;
  libraryFileId: string | null;
}

export interface RefreshScanResult {
  metadataRefreshed: boolean;
  filesLinked: number;
}

export interface PreviewRenameResult {
  currentPath: string;
  targetPath: string;
  changed: boolean;
}

export type NotificationEventType = "GRAB" | "IMPORT" | "BLOCKLIST";

export interface Notifier {
  id: string;
  name: string;
  type: string;
  urlSet: boolean;
  tokenSet: boolean;
  target: string | null;
  enabled: boolean;
  /** Empty means every event type. */
  enabledEvents: NotificationEventType[];
  createdAt: string;
}

export interface JellyfinServer {
  id: string;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  enabled: boolean;
  createdAt: string;
  selectedLibraryIds: string[];
  selectedUserIds: string[];
}

export interface RadarrServer {
  id: string;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  enabled: boolean;
  createdAt: string;
}

export interface SonarrServer {
  id: string;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  enabled: boolean;
  createdAt: string;
}

export interface JellyfinLibrary {
  id: string;
  name: string;
  collectionType: string | null;
  locations: string[];
}

export interface JellyfinUser {
  id: string;
  name: string;
  isAdmin: boolean;
}

export interface SetupStatus {
  needsSetup: boolean;
}

export type JobCategory = "KOSMOS" | "SERVER";

export interface ScheduledJob {
  id: string;
  name: string;
  displayName: string;
  category: JobCategory;
  intervalSeconds: number;
  enabled: boolean;
  running: boolean;
  lastRunAt: string | null;
  lastStatus: string | null;
  lastMessage: string | null;
}

/** One SSE frame from GET /jobs/{name}/progress — see useJobProgress. */
export interface JobProgressEvent {
  kind: "started" | "progress" | "finished";
  current: number | null;
  total: number | null;
  message: string | null;
  status: string | null;
}

export interface JobRun {
  id: string;
  startedAt: string;
  finishedAt: string | null;
  status: string;
  message: string | null;
}

/** One row of the Jobs page's global "Recent Activity" section — GET /jobs/runs. */
export interface RecentJobRun {
  id: string;
  jobName: string;
  jobDisplayName: string;
  startedAt: string;
  finishedAt: string | null;
  status: string;
  message: string | null;
}

export interface MetadataStatus {
  tmdbConfigured: boolean;
}

export interface TmdbTestResult {
  ok: boolean;
}

export interface LibraryStats {
  movieCount: number;
  seriesCount: number;
  animeCount: number;
  needsReviewCount: number;
  usedBytes: number;
  totalBytes: number | null;
}

/** Season 0 is specials, matching Jellyfin/TMDB convention. */
export interface SeasonEpisodeCount {
  seasonNumber: number;
  episodeCount: number;
}

/**
 * A Jellyfin "tvshows" item the sync found an anime signal for but couldn't confirm from AniList —
 * neither a Show nor an Anime row exists for it yet. `anilistId` is Jellyfin's own reported id (if
 * any) shown only as a hint; it's the same id that failed to resolve.
 */
export interface UnclassifiedShow {
  id: string;
  name: string;
  year: number | null;
  tmdbId: string;
  anilistId: string | null;
  posterPath: string | null;
  overview: string | null;
  reason: "ANILIST_MATCH_UNCONFIRMED" | "ANILIST_MATCH_AMBIGUOUS";
  seasons: SeasonEpisodeCount[];
  detectedAt: string;
}

export type LibraryContentType = "movie" | "show" | "anime";

export interface LibraryRootFolder {
  id: string;
  path: string;
  contentTypes: LibraryContentType[];
  createdAt: string;
}

export interface NamingSettings {
  contentType: "movie" | "show" | "anime";
  folderTemplate: string;
  fileTemplate: string;
  /** Show only — null for movie/anime, which have no season subfolder. */
  seasonFolderTemplate: string | null;
}

export interface DirectoryEntry {
  name: string;
  path: string;
}

export interface BrowseResult {
  path: string;
  parentPath: string | null;
  directories: DirectoryEntry[];
}

export interface TestIndexerResult {
  ok: boolean;
  message: string;
}

export interface TestDownloadClientResult {
  ok: boolean;
  message: string;
}

export interface ImportFromProwlarrResult {
  imported: number;
  skippedDisabled: number;
}

export interface DiscoverItem {
  mediaItemId: string | null;
  externalId: string | null;
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  voteAverage: number | null;
  mediaType: "movie" | "tv";
  inLibrary: boolean;
  partiallyAvailable: boolean;
}

export interface GenreTile {
  id: number;
  name: string;
}

export interface BecauseYouAddedResult {
  basedOnTitle: string;
  items: DiscoverItem[];
}

export interface StudioTile {
  id: number;
  name: string;
  logoPath: string;
}

export type RequestStatus = "PENDING" | "APPROVED" | "AVAILABLE" | "DECLINED";

export interface MediaRequest {
  id: string;
  requestedByDisplayName: string;
  mine: boolean;
  mediaType: "movie" | "tv";
  externalId: string;
  pluginSlug: string;
  title: string;
  year: number | null;
  overview: string | null;
  posterPath: string | null;
  backdropPath: string | null;
  qualityProfileId: string | null;
  qualityProfileName: string | null;
  status: RequestStatus;
  note: string | null;
  mediaItemId: string | null;
  requestedAt: string;
  decidedAt: string | null;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  role: "ADMIN" | "USER";
  jellyfinLinked: boolean;
  createdAt: string;
}

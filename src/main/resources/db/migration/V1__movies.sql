-- Primary keys are UUIDs stored as VARCHAR(36), generated in application code
-- (Jakarta Persistence GenerationType.UUID). No database-side identity/sequence
-- mechanism is used, so this DDL is portable to any relational datasource.

CREATE TABLE plugin (
    id                VARCHAR(36) PRIMARY KEY,
    slug              VARCHAR(100) NOT NULL UNIQUE,
    name              VARCHAR(200) NOT NULL,
    kind              VARCHAR(20) NOT NULL,
    built_in          BOOLEAN NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    installed_at      TIMESTAMP NOT NULL
);

-- Where grabbed releases for a title land — the Radarr/Sonarr "Root Folders" concept, one or
-- more registered per install, chosen per title at add time.
CREATE TABLE library_root_folder (
    id             VARCHAR(36) PRIMARY KEY,
    path           VARCHAR(1000) NOT NULL UNIQUE,
    content_types  VARCHAR(200),
    created_at     TIMESTAMP NOT NULL
);

CREATE TABLE media_item (
    id                VARCHAR(36) PRIMARY KEY,
    content_type      VARCHAR(20) NOT NULL,
    title             VARCHAR(500) NOT NULL,
    year              INTEGER,
    added_at          TIMESTAMP NOT NULL,
    root_folder_id    VARCHAR(36) REFERENCES library_root_folder(id)
);

-- catalog cross-reference: the WORK, not a file — used for metadata fetching
CREATE TABLE media_item_external_id (
    id                VARCHAR(36) PRIMARY KEY,
    media_item_id     VARCHAR(36) NOT NULL REFERENCES media_item(id),
    plugin_id         VARCHAR(36) NOT NULL REFERENCES plugin(id),
    external_id       VARCHAR(200) NOT NULL,
    matched_at        TIMESTAMP NOT NULL,
    superseded_at     TIMESTAMP
);

-- the physical FILE, and how THIS file got linked to a media_item
CREATE TABLE library_file (
    id                VARCHAR(36) PRIMARY KEY,
    media_item_id     VARCHAR(36) REFERENCES media_item(id),
    path              VARCHAR(1000) NOT NULL UNIQUE,
    size_bytes        BIGINT NOT NULL,

    match_method      VARCHAR(20) NOT NULL,
    match_confidence  REAL NOT NULL,
    match_pinned      BOOLEAN NOT NULL DEFAULT FALSE,
    matched_at        TIMESTAMP,

    container         VARCHAR(20),
    video_codec       VARCHAR(30),
    resolution_width  INTEGER,
    resolution_height INTEGER,
    duration_seconds  INTEGER,
    hdr_format        VARCHAR(30),
    bit_depth         INTEGER,
    probed_at         TIMESTAMP,
    embedded_title    VARCHAR(500),
    verified          BOOLEAN NOT NULL DEFAULT FALSE,

    imported_at       TIMESTAMP NOT NULL
);

CREATE TABLE library_file_hash (
    id                VARCHAR(36) PRIMARY KEY,
    library_file_id   VARCHAR(36) NOT NULL REFERENCES library_file(id),
    algorithm         VARCHAR(20) NOT NULL,
    value             VARCHAR(200) NOT NULL
);

CREATE TABLE library_file_embedded_id (
    id                VARCHAR(36) PRIMARY KEY,
    library_file_id   VARCHAR(36) NOT NULL REFERENCES library_file(id),
    label             VARCHAR(50) NOT NULL,
    raw_value         VARCHAR(200) NOT NULL
);

CREATE TABLE library_file_track (
    id                VARCHAR(36) PRIMARY KEY,
    library_file_id   VARCHAR(36) NOT NULL REFERENCES library_file(id),
    track_type        VARCHAR(20) NOT NULL,
    codec             VARCHAR(30),
    language          VARCHAR(10),
    channels          INTEGER
);

-- Movie catalog

CREATE TABLE movie (
    media_item_id          VARCHAR(36) PRIMARY KEY REFERENCES media_item(id),
    runtime_minutes        INTEGER,
    overview               VARCHAR(4000),
    poster_path            VARCHAR(500),
    backdrop_path          VARCHAR(500),
    -- TMDB's theatrical release date — see MinimumAvailability.
    release_date           DATE,
    -- TMDB's US digital release date — see MinimumAvailability.
    digital_release_date   DATE,
    minimum_availability   VARCHAR(20) NOT NULL DEFAULT 'RELEASED'
);

-- Hard reject/allow size gate, applied before custom-format scoring (a release under the floor
-- is a fake/sample problem, not a "which valid release scores higher" problem — see
-- ScoringEngine and the resolution+source lookup in QualityDefinitionService.checkSizeGate).
CREATE TABLE quality_definition (
    id                 VARCHAR(36) PRIMARY KEY,
    resolution         VARCHAR(20) NOT NULL,
    source             VARCHAR(30) NOT NULL,
    min_mb_per_minute  DOUBLE PRECISION NOT NULL,
    max_mb_per_minute  DOUBLE PRECISION NOT NULL,
    UNIQUE (resolution, source)
);

CREATE TABLE quality_profile (
    id                  VARCHAR(36) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    cutoff_score        INTEGER NOT NULL,
    -- Simplified Delay Profile — see QualityProfile#grabDelayMinutes/#bypassScore.
    grab_delay_minutes  INTEGER NOT NULL DEFAULT 0,
    bypass_score        INTEGER
);

CREATE TABLE custom_format (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    score             INTEGER NOT NULL,
    rule              VARCHAR(16000) NOT NULL,
    trash_id          VARCHAR(64)
);

CREATE TABLE quality_profile_format (
    quality_profile_id VARCHAR(36) NOT NULL REFERENCES quality_profile(id),
    custom_format_id   VARCHAR(36) NOT NULL REFERENCES custom_format(id),
    PRIMARY KEY (quality_profile_id, custom_format_id)
);

-- Nullable: a movie with no profile assigned is simply not eligible for automatic search yet.
ALTER TABLE movie ADD COLUMN quality_profile_id VARCHAR(36) REFERENCES quality_profile(id);

-- Show catalog (Phase 2) — mirrors Movie's shared-PK pattern with media_item. Season/episode are
-- plain metadata rows for now, not media_items themselves — see Episode's own comment.
CREATE TABLE show (
    media_item_id         VARCHAR(36) PRIMARY KEY REFERENCES media_item(id),
    overview              VARCHAR(4000),
    poster_path           VARCHAR(500),
    backdrop_path         VARCHAR(500),
    status                VARCHAR(30),
    quality_profile_id    VARCHAR(36) REFERENCES quality_profile(id),
    -- Null means "use the global show naming settings" — see Show#seasonFolderEnabled.
    season_folder_enabled BOOLEAN
);

CREATE TABLE season (
    id                VARCHAR(36) PRIMARY KEY,
    show_id           VARCHAR(36) NOT NULL REFERENCES show(media_item_id),
    season_number     INTEGER NOT NULL,
    name              VARCHAR(200) NOT NULL,
    overview          VARCHAR(4000),
    poster_path       VARCHAR(500),
    episode_count     INTEGER
);

-- shares its PK with media_item, same pattern as movie/show — this is what makes an episode
-- individually gradable/searchable: release/grab/library_file already FK to media_item_id
-- generically, so nothing about those tables changes to support per-episode tracking.
CREATE TABLE episode (
    media_item_id     VARCHAR(36) PRIMARY KEY REFERENCES media_item(id),
    season_id         VARCHAR(36) NOT NULL REFERENCES season(id),
    episode_number    INTEGER NOT NULL,
    overview          VARCHAR(4000),
    air_date          DATE,
    runtime_minutes   INTEGER,
    still_path        VARCHAR(500)
);

CREATE TABLE release (
    id                VARCHAR(36) PRIMARY KEY,
    media_item_id     VARCHAR(36) NOT NULL REFERENCES media_item(id),
    title_raw         VARCHAR(500) NOT NULL,
    download_url      VARCHAR(2000) NOT NULL,
    parsed_resolution VARCHAR(20),
    parsed_codec      VARCHAR(30),
    parsed_source     VARCHAR(30),
    score             INTEGER,
    found_at          TIMESTAMP NOT NULL
);

CREATE TABLE indexer (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    base_url          VARCHAR(1000) NOT NULL,
    api_key           VARCHAR(200) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL
);

CREATE TABLE download_client (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    type              VARCHAR(30) NOT NULL DEFAULT 'QBITTORRENT',
    base_url          VARCHAR(1000) NOT NULL,
    username          VARCHAR(200),
    password          VARCHAR(200),
    category          VARCHAR(100),
    -- Path remapping for a client that doesn't see the same filesystem Kosmos does (split-host,
    -- Docker with a different bind mount). Both null/blank means no remapping — DownloadClient's
    -- own remapPath() then returns the reported content path unchanged, same as before this
    -- existed. See DownloadStatusPollJob.
    remote_path       VARCHAR(1000),
    local_path        VARCHAR(1000),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL
);

CREATE TABLE grab (
    id                  VARCHAR(36) PRIMARY KEY,
    release_id          VARCHAR(36) NOT NULL REFERENCES release(id),
    download_client_id  VARCHAR(36) NOT NULL REFERENCES download_client(id),
    -- info-hash for a torrent grab, job/queue id for a Usenet one — whatever a client can poll
    -- status by. Null when nothing was determinable at grab time (see GrabService).
    job_id              VARCHAR(100),
    status              VARCHAR(30) NOT NULL,
    grabbed_at          TIMESTAMP NOT NULL
);

CREATE TABLE scheduled_job (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(100) NOT NULL UNIQUE,
    display_name      VARCHAR(200) NOT NULL,
    interval_seconds  INTEGER NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    -- set for the duration of a run, so a concurrent tick can see "already running" the instant
    -- a job is claimed rather than only after the whole run (including its own external HTTP
    -- calls) commits. Null when idle.
    running_since     TIMESTAMP,
    last_run_at       TIMESTAMP,
    last_status       VARCHAR(20),
    last_message      VARCHAR(1000),
    -- first-seen order, so the jobs settings page has a stable row order — without one to sort
    -- by, Postgres returns whatever physical order it likes, which can shift on every UPDATE.
    created_at        TIMESTAMP NOT NULL DEFAULT now()
);

CREATE TABLE job_run (
    id                 VARCHAR(36) PRIMARY KEY,
    scheduled_job_id   VARCHAR(36) NOT NULL REFERENCES scheduled_job(id),
    started_at         TIMESTAMP NOT NULL,
    finished_at        TIMESTAMP,
    status             VARCHAR(20) NOT NULL,
    message            VARCHAR(1000)
);

CREATE TABLE notifier (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    type              VARCHAR(20) NOT NULL,
    url               VARCHAR(1000),
    token             VARCHAR(500),
    target            VARCHAR(200),
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    -- comma-separated NotificationEventType names this notifier fires for; null/blank means every
    -- event, so a notifier created before this column existed keeps behaving exactly as before.
    enabled_events    VARCHAR(200),
    created_at        TIMESTAMP NOT NULL
);

CREATE TABLE jellyfin_server (
    id                    VARCHAR(36) PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    base_url              VARCHAR(1000) NOT NULL,
    api_key               VARCHAR(200) NOT NULL,
    selected_library_ids  VARCHAR(2000),
    -- comma-separated Jellyfin user ids; null/blank means "import every account" — same
    -- convention as selected_library_ids.
    selected_user_ids     VARCHAR(4000),
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMP NOT NULL
);

-- A configured Radarr server to import an already-managed movie library from.
CREATE TABLE radarr_server (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    base_url          VARCHAR(1000) NOT NULL,
    api_key           VARCHAR(200) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL
);

-- A configured Sonarr server to import an already-managed series library from.
CREATE TABLE sonarr_server (
    id                VARCHAR(36) PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    base_url          VARCHAR(1000) NOT NULL,
    api_key           VARCHAR(200) NOT NULL,
    enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP NOT NULL
);

-- Either a native account (password_hash set, jellyfin_* null) or a Jellyfin-linked account
-- (jellyfin_server_id + jellyfin_user_id set, password_hash null — login always proxies to
-- that Jellyfin server, and role is kept in sync with its own Policy.IsAdministrator flag).
CREATE TABLE app_user (
    id                  VARCHAR(36) PRIMARY KEY,
    username            VARCHAR(100) NOT NULL UNIQUE,
    display_name        VARCHAR(200) NOT NULL,
    password_hash       VARCHAR(200),
    jellyfin_server_id  VARCHAR(36) REFERENCES jellyfin_server(id),
    jellyfin_user_id    VARCHAR(100),
    role                VARCHAR(20) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL
);

CREATE TABLE user_session (
    token       VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(36) NOT NULL REFERENCES app_user(id),
    created_at  TIMESTAMP NOT NULL,
    expires_at  TIMESTAMP NOT NULL
);

-- a title someone asked for, before (or instead of) it becomes a movie/show row
CREATE TABLE request (
    id                    VARCHAR(36) PRIMARY KEY,
    -- Null for a list-sourced request (see source_list_name) — every user-filed request still
    -- requires one.
    requested_by_id       VARCHAR(36) REFERENCES app_user(id),
    -- Set only for a request ImportListSyncJob filed, not a user — "List: <name>" is what the
    -- Requests queue shows in place of a requester for these. Mutually exclusive with
    -- requested_by_id in practice, though nothing besides application code enforces that.
    source_list_name      VARCHAR(200),
    media_type            VARCHAR(10) NOT NULL,
    external_id           VARCHAR(200) NOT NULL,
    plugin_slug           VARCHAR(100) NOT NULL,
    title                 VARCHAR(500) NOT NULL,
    year                  INTEGER,
    overview              VARCHAR(4000),
    poster_path           VARCHAR(500),
    backdrop_path         VARCHAR(500),
    quality_profile_id    VARCHAR(36) REFERENCES quality_profile(id),
    status                VARCHAR(20) NOT NULL,
    note                  VARCHAR(1000),
    media_item_id         VARCHAR(36) REFERENCES media_item(id),
    requested_at          TIMESTAMP NOT NULL,
    decided_by_id         VARCHAR(36) REFERENCES app_user(id),
    decided_at            TIMESTAMP
);

-- anime-specific attributes for a MediaItem, sharing its PK like movie/show. Represents a whole
-- franchise (every cour a TMDB TV id's own season carries), not a single AniList entry — see
-- anime_season.
CREATE TABLE anime (
    media_item_id      VARCHAR(36) PRIMARY KEY REFERENCES media_item(id),
    overview           VARCHAR(4000),
    poster_path        VARCHAR(500),
    backdrop_path      VARCHAR(500),
    status             VARCHAR(30),
    episode_count_total INTEGER,
    quality_profile_id VARCHAR(36) REFERENCES quality_profile(id)
);

-- One AniList cour of an anime franchise. AniList/AniDB don't model seasons themselves — a new
-- cour is normally a wholly separate anime entry there — so season_number and the episode
-- ordering that anchors absolute_episode_number both come from Fribb/anime-lists' TMDB
-- cross-reference (season.tmdb) instead: every AniList id sharing the franchise's TMDB TV id,
-- ordered by that TMDB season number.
CREATE TABLE anime_season (
    id              VARCHAR(36) PRIMARY KEY,
    anime_id        VARCHAR(36) NOT NULL REFERENCES anime(media_item_id),
    season_number   INTEGER NOT NULL,
    name            VARCHAR(200) NOT NULL,
    overview        VARCHAR(4000),
    episode_count   INTEGER,
    anilist_id      VARCHAR(200),
    -- how many episodes precede this season in the franchise's continuous absolute numbering;
    -- season 0 (specials) is excluded from that count and numbers its own episodes from 1.
    absolute_offset INTEGER NOT NULL DEFAULT 0
);

-- shares its PK with media_item too, same reasoning as episode: an anime episode needs to be
-- individually gradable/searchable the same way a TV episode is.
CREATE TABLE anime_episode (
    media_item_id           VARCHAR(36) PRIMARY KEY REFERENCES media_item(id),
    season_id               VARCHAR(36) NOT NULL REFERENCES anime_season(id),
    episode_number          INTEGER,
    absolute_episode_number INTEGER,
    -- EPISODE / SPECIAL / OP / ED / TRAILER / PARODY / OTHER, per AniDB's own episode-type model
    episode_type            VARCHAR(20) NOT NULL,
    overview                VARCHAR(4000),
    air_date                DATE,
    runtime_minutes         INTEGER,
    still_path              VARCHAR(500)
);

-- A Jellyfin "tvshows" item JellyfinSyncService couldn't confidently route to show or anime: some
-- signal pointed at anime (Jellyfin's own AniList ProviderId, or a Fribb/TMDB reverse match) but
-- AniList itself returned nothing for that id, so there's nothing to enrich an Anime row from and
-- no proof the match is even real. Sits outside media_item entirely — neither a Show nor an Anime
-- gets created until a user resolves it (JellyfinSyncService#resolveAsShow/resolveAsAnime), so it
-- never pollutes either collection while pending.
CREATE TABLE unclassified_show (
    id                 VARCHAR(36) PRIMARY KEY,
    jellyfin_server_id VARCHAR(36) NOT NULL REFERENCES jellyfin_server(id),
    jellyfin_item_id   VARCHAR(200) NOT NULL,
    name               VARCHAR(500) NOT NULL,
    year               INTEGER,
    tmdb_id            VARCHAR(200) NOT NULL,
    anilist_id         VARCHAR(200),
    path               VARCHAR(1000) NOT NULL,
    reason             VARCHAR(40) NOT NULL,
    -- [{"seasonNumber":1,"episodeCount":12}, ...] snapshotted from Jellyfin's own scanned episode
    -- files at flag time — lets the review screen show a season/episode breakdown without a live
    -- Jellyfin call per row, and gives the sync's own classifier a signal to narrow matches on.
    seasons_json       VARCHAR(2000),
    detected_at        TIMESTAMP NOT NULL,
    UNIQUE (jellyfin_server_id, jellyfin_item_id)
);

-- A release DownloadStatusPollJob (or a user) confirmed failed for a specific media item — kept
-- per-item, not global, since the same release can be a bad match for one title's search and
-- irrelevant to every other. AutomaticSearchJob/TvAutomaticSearchJob/AnimeAutomaticSearchJob skip
-- any candidate whose download_url is already blocklisted for the media item being searched,
-- rather than re-grabbing the same dead release every run.
-- One row per content type (movie/show/anime), each holding that type's on-disk naming templates
-- (see library.naming.NamingTemplateEngine for the {Token} syntax). No row is required to exist —
-- NamingSettingsService falls back to the same defaults ImportService used to hardcode, so an
-- instance nobody has customized behaves exactly as before.
CREATE TABLE naming_settings (
    id                      VARCHAR(36) PRIMARY KEY,
    content_type            VARCHAR(20) NOT NULL UNIQUE,
    folder_template         VARCHAR(500) NOT NULL,
    file_template           VARCHAR(500) NOT NULL,
    -- show only; null for movie/anime, which have no season subfolder.
    season_folder_template  VARCHAR(200)
);

CREATE TABLE blocklist (
    id             VARCHAR(36) PRIMARY KEY,
    media_item_id  VARCHAR(36) NOT NULL REFERENCES media_item(id),
    download_url   VARCHAR(2000) NOT NULL,
    title_raw      VARCHAR(500) NOT NULL,
    reason         VARCHAR(500) NOT NULL,
    blocked_at     TIMESTAMP NOT NULL,
    UNIQUE (media_item_id, download_url)
);

-- A configured list feed (see ImportListService) — syncing one files a Request per new candidate
-- rather than silently adding it, unless trusted is set.
CREATE TABLE import_list (
    id                  VARCHAR(36) PRIMARY KEY,
    name                VARCHAR(200) NOT NULL,
    source_type         VARCHAR(50) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    trusted             BOOLEAN NOT NULL DEFAULT FALSE,
    quality_profile_id  VARCHAR(36) REFERENCES quality_profile(id),
    created_at          TIMESTAMP NOT NULL,
    last_synced_at      TIMESTAMP
);

-- A permanent "never suggest this title again", checked by every list's sync — ports directly
-- from Radarr/Sonarr's own List Exclusions.
CREATE TABLE import_list_exclusion (
    id             VARCHAR(36) PRIMARY KEY,
    plugin_slug    VARCHAR(100) NOT NULL,
    external_id    VARCHAR(200) NOT NULL,
    title          VARCHAR(500) NOT NULL,
    excluded_at    TIMESTAMP NOT NULL,
    UNIQUE (plugin_slug, external_id)
);

-- A TMDB movie collection someone has monitored (see MovieCollectionService) — only ever persisted
-- once monitoring starts; browsing an unmonitored collection reads TMDB live.
CREATE TABLE movie_collection (
    id                  VARCHAR(36) PRIMARY KEY,
    tmdb_collection_id  VARCHAR(50) NOT NULL UNIQUE,
    name                VARCHAR(500) NOT NULL,
    poster_path         VARCHAR(500),
    backdrop_path       VARCHAR(500),
    monitored           BOOLEAN NOT NULL DEFAULT TRUE,
    quality_profile_id  VARCHAR(36) REFERENCES quality_profile(id),
    created_at          TIMESTAMP NOT NULL,
    last_synced_at      TIMESTAMP
);

-- The current best release AutomaticSearchJob has seen for one media item, so a quality profile's
-- grab_delay_minutes can be honored across job runs — see PendingCandidate's own doc.
CREATE TABLE pending_candidate (
    id             VARCHAR(36) PRIMARY KEY,
    media_item_id  VARCHAR(36) NOT NULL UNIQUE REFERENCES media_item(id),
    download_url   VARCHAR(2000) NOT NULL,
    first_seen_at  TIMESTAMP NOT NULL
);

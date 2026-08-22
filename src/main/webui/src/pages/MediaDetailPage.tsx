import {
  CheckCircleIcon as CheckCircle,
  ListMagnifyingGlassIcon as ListMagnifyingGlass,
  MagnifyingGlassIcon as MagnifyingGlass,
  PlayCircleIcon as PlayCircle,
  PlusIcon as Plus,
  SparkleIcon as Sparkle,
  SpinnerIcon as Spinner,
  StarIcon as Star,
  TelevisionIcon as Television,
  TrashIcon as Trash,
} from "@phosphor-icons/react";
import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api } from "../api/client";
import { backdropUrl, posterUrl } from "../api/tmdbImage";
import type { AnimeDetail, AnimeSeason, EpisodeStatus, Movie, PreviewEpisode, PreviewSeason, Season, ShowDetail } from "../api/types";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { CastRow, SimilarRow } from "../components/DetailExtrasSections";
import { GroupedEpisodeList, type SeasonRowData } from "../components/detail/EpisodeList";
import { FileStatusCard } from "../components/detail/FileStatusCard";
import { MoreActionsMenu } from "../components/detail/MoreActionsMenu";
import { QualityProfileDropdown } from "../components/detail/QualityProfileDropdown";
import { SeriesMonitoringDropdown } from "../components/detail/SeriesMonitoringDropdown";
import { Toggle } from "../components/Toggle";
import { useAddToLibrary } from "../hooks/useAddToLibrary";
import { useArtworkFallback } from "../hooks/useArtworkFallback";
import { mediaTypeFor, type MediaKind, ownedPathFor, useMediaDetail } from "../hooks/useMediaDetail";

function seasonsFromPreview(seasons: PreviewSeason[]): Season[] {
  return seasons.map((s) => ({
    id: `preview-season-${s.seasonNumber}`,
    seasonNumber: s.seasonNumber,
    name: s.name,
    overview: null,
    posterPath: null,
    episodeCount: s.episodeCount,
    episodes: s.episodes.map((e) => ({
      id: `preview-episode-${s.seasonNumber}-${e.episodeNumber}`,
      episodeNumber: e.episodeNumber,
      title: e.title,
      overview: null,
      airDate: e.airDate,
      runtimeMinutes: null,
      stillPath: null,
      monitored: true,
      status: "MISSING" as EpisodeStatus,
    })),
  }));
}

function animeSeasonsFromPreview(seasons: PreviewSeason[]): AnimeSeason[] {
  return seasons.map((s) => ({
    id: `preview-season-${s.seasonNumber}`,
    seasonNumber: s.seasonNumber,
    name: s.name,
    overview: null,
    episodeCount: s.episodeCount,
    episodes: s.episodes.map((e) => ({
      id: `preview-episode-${s.seasonNumber}-${e.episodeNumber}`,
      episodeNumber: e.episodeNumber,
      absoluteEpisodeNumber: e.absoluteEpisodeNumber,
      episodeType: "EPISODE",
      title: e.title,
      overview: null,
      airDate: e.airDate,
      runtimeMinutes: null,
      stillPath: null,
      monitored: true,
      status: "MISSING" as EpisodeStatus,
    })),
  }));
}

/**
 * The one detail screen for movies, shows, and anime alike — owned or not. Seerr uses a single
 * layout for every media type bar a seasons list on shows; Kosmos now does the same. {@code kind}
 * (from the matching route in App.tsx) picks which real entity/preview endpoints {@link
 * useMediaDetail} calls and which of the few genuinely type-specific bits below render — the
 * hero/poster/title/genres/synopsis/cast/similar layout and the owned-vs-not-owned/availability
 * states are otherwise identical, so none of that is duplicated per type.
 */
export default function MediaDetailPage({ kind }: { kind: MediaKind }) {
  const {
    owned,
    ownedMedia,
    ownedLoading,
    ownedError,
    extras,
    libraryFiles,
    profiles,
    preview,
    previewLoading,
    previewError,
    setQualityProfile,
    setMinimumAvailability,
    setSeasonFolderEnabled,
    setEpisodeMonitored,
    setSeriesMonitoring,
  } = useMediaDetail(kind);
  const { admin, stateFor, triggerAdd } = useAddToLibrary();
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteFiles, setDeleteFiles] = useState(false);

  async function confirmDelete() {
    if (!ownedMedia) return;
    if (kind === "movie") await api.deleteMovie(ownedMedia.id, deleteFiles);
    else if (kind === "show") await api.deleteShow(ownedMedia.id, deleteFiles);
    else await api.deleteAnime(ownedMedia.id, deleteFiles);
    navigate("/library");
  }

  const title = ownedMedia?.title ?? preview?.title ?? "";
  const year = ownedMedia?.year ?? preview?.year ?? null;
  const overview = ownedMedia?.overview ?? preview?.overview ?? null;
  const posterPath = ownedMedia?.posterPath ?? preview?.posterPath ?? null;
  const backdropPath = ownedMedia?.backdropPath ?? preview?.backdropPath ?? null;
  const genres = extras?.genres ?? preview?.genres ?? [];
  const facts = extras?.facts ?? preview?.facts ?? [];
  const cast = extras?.cast ?? preview?.cast ?? [];
  const similar = extras?.similar ?? preview?.similar ?? [];
  const voteAverage = extras?.voteAverage ?? preview?.voteAverage ?? null;
  const certification = extras?.certification ?? preview?.certification ?? null;
  const trailerUrl = extras?.trailerUrl ?? preview?.trailerUrl ?? null;
  const collection = extras?.collection ?? null;
  const director = kind === "movie" ? facts.find((f) => f.k === "Director")?.v : undefined;

  const { url: posterSrc, probe: posterProbe } = useArtworkFallback(posterUrl(posterPath, "w500"), ownedMedia?.id, "poster");
  const { url: backdropArt, probe: backdropProbe } = useArtworkFallback(backdropUrl(backdropPath), ownedMedia?.id, "backdrop");

  if (ownedLoading || previewLoading) return <div className="page">Loading…</div>;
  if (owned && ownedError) return <div className="page text-muted">Failed to load: {ownedError}</div>;
  if (!owned && previewError) return <div className="page text-muted">Failed to load: {previewError}</div>;
  if (owned && !ownedMedia) return null;
  if (!owned && !preview) return null;

  const activeProfile = profiles?.find((p) => p.id === ownedMedia?.qualityProfileId) ?? null;
  const file = kind === "movie" ? (libraryFiles[0] ?? null) : null;

  let groupedSeasons: SeasonRowData[] | null = null;
  if (kind === "show") {
    const seasons = owned && ownedMedia ? (ownedMedia as ShowDetail).seasons : seasonsFromPreview(preview?.seasons ?? []);
    groupedSeasons = seasons.map((s) => ({
      id: s.id,
      seasonNumber: s.seasonNumber,
      name: s.name,
      episodeCount: s.episodeCount,
      episodes: s.episodes.map((e) => ({
        id: e.id,
        number: e.episodeNumber,
        title: e.title,
        airDate: e.airDate,
        monitored: owned ? e.monitored : true,
        status: e.status,
        searchHref: owned ? `/episodes/${e.id}/search` : null,
      })),
    }));
  } else if (kind === "anime") {
    const seasons = owned && ownedMedia ? (ownedMedia as AnimeDetail).seasons : animeSeasonsFromPreview(preview?.seasons ?? []);
    groupedSeasons = seasons.map((s) => ({
      id: s.id,
      seasonNumber: s.seasonNumber,
      name: s.name,
      episodeCount: s.episodeCount,
      episodes: s.episodes.map((e) => ({
        id: e.id,
        number: e.episodeNumber,
        absoluteNumber: e.absoluteEpisodeNumber,
        title: e.title,
        airDate: e.airDate,
        monitored: owned ? e.monitored : true,
        status: e.status,
        searchHref: owned ? `/anime-episodes/${e.id}/search` : null,
      })),
    }));
  }

  let availability: "good" | "warn" | "bad" = "bad";
  let availabilityLabel = "Missing";
  if (kind === "movie") {
    availability = file ? "good" : "bad";
    availabilityLabel = file ? "In Library" : "Missing";
  } else {
    const episodes = (groupedSeasons ?? []).flatMap((s) => s.episodes);
    const availableCount = episodes.filter((e) => e.status === "AVAILABLE").length;
    availability = availableCount === 0 ? "bad" : availableCount === episodes.length ? "good" : "warn";
    availabilityLabel = availability === "good" ? "In Library" : availability === "warn" ? "Partially Available" : "Missing";
  }

  const addState = preview ? stateFor(preview.externalId) : "idle";
  const addLabel =
    addState === "adding"
      ? admin
        ? "Adding…"
        : "Requesting…"
      : addState === "added"
        ? admin
          ? "Added"
          : "Requested"
        : admin
          ? "Add to Library"
          : "Request";

  return (
    <div>
      {backdropProbe}
      {posterProbe}
      <section
        className="detail-hero"
        style={backdropArt ? { backgroundImage: `url(${backdropArt})`, backgroundSize: "cover", backgroundPosition: "center" } : undefined}
      >
        {trailerUrl && (
          <a href={trailerUrl} target="_blank" rel="noopener noreferrer" className="chip-floating detail-hero-trailer">
            <PlayCircle size={20} weight="fill" />
            Trailer
          </a>
        )}
      </section>

      <div className="detail-body2">
        <div className="detail-poster2">
          <div className="detail-poster2-art">{posterSrc && <img src={posterSrc} alt="" />}</div>
        </div>

        <div className="detail-body2-main">
          <div className="detail-title-row">
            {owned ? (
              <>
                <span className={`status-pill ${availability}`}>
                  <span className="dot" />
                  {availabilityLabel}
                </span>
                <span className="detail-title-note">
                  {activeProfile ? `monitored · automatic search every 6h against "${activeProfile.name}"` : "not monitored"}
                </span>
              </>
            ) : (
              <span className="status-pill accent">Not in Library</span>
            )}
          </div>

          <h1 className="detail-h1">{title}</h1>

          <div className="detail-meta-row2">
            {year && <span>{year}</span>}
            {kind === "movie" && owned && (
              <>
                <span className="sep" />
                <span>
                  {(ownedMedia as Movie).runtimeMinutes
                    ? `${Math.floor((ownedMedia as Movie).runtimeMinutes! / 60)}h ${(ownedMedia as Movie).runtimeMinutes! % 60}m`
                    : "Runtime unknown"}
                </span>
              </>
            )}
            {(kind === "show" || kind === "anime") && groupedSeasons && groupedSeasons.length > 0 && (
              <>
                <span className="sep" />
                <span>
                  {groupedSeasons.length} season{groupedSeasons.length === 1 ? "" : "s"}
                </span>
                <span className="sep" />
                <span>{groupedSeasons.reduce((sum, s) => sum + s.episodes.length, 0)} episodes</span>
              </>
            )}
            {owned && kind === "show" && (ownedMedia as ShowDetail)?.status && (
              <>
                <span className="sep" />
                <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
                  <Television size={12} />
                  {(ownedMedia as ShowDetail).status}
                </span>
              </>
            )}
            {owned && kind === "anime" && (ownedMedia as AnimeDetail)?.status && (
              <>
                <span className="sep" />
                <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
                  <Sparkle size={12} />
                  {(ownedMedia as AnimeDetail).status}
                </span>
              </>
            )}
            {certification && (
              <>
                <span className="sep" />
                <span className="cert-badge">{certification}</span>
              </>
            )}
            {voteAverage != null && (
              <>
                <span className="sep" />
                <span style={{ display: "inline-flex", alignItems: "center", gap: 5 }}>
                  <Star size={12} weight="fill" color="#E0A94A" />
                  {voteAverage.toFixed(1)} <span className="text-ghost">{kind === "anime" ? "AniList" : "TMDB"}</span>
                </span>
              </>
            )}
            {director && (
              <>
                <span className="sep" />
                <span>{director}</span>
              </>
            )}
          </div>

          {genres.length > 0 && (
            <div className="detail-genres">
              {genres.map((g) => (
                <span key={g} className="genre-tag">
                  {g}
                </span>
              ))}
            </div>
          )}

          <div style={kind === "movie" ? { display: "flex", flexDirection: "column", gap: 18 } : undefined}>
            {owned ? (
              kind === "movie" ? (
                <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
                  <Link to={`/movies/${ownedMedia!.id}/search`} className="btn btn-hero">
                    <MagnifyingGlass size={16} weight="bold" />
                    Search Now
                  </Link>
                  <Link to={`/movies/${ownedMedia!.id}/search`} className="btn btn-secondary">
                    <ListMagnifyingGlass size={15} />
                    Interactive search
                  </Link>
                  <QualityProfileDropdown profiles={profiles} activeProfile={activeProfile} onSelect={setQualityProfile} />
                  <div className="seg" title="How soon after announcement automatic search may grab this movie">
                    {(["ANNOUNCED", "IN_CINEMAS", "RELEASED"] as const).map((option) => (
                      <button
                        key={option}
                        type="button"
                        className={(ownedMedia as Movie).minimumAvailability === option ? "active" : ""}
                        onClick={() => setMinimumAvailability(option)}
                      >
                        {option === "ANNOUNCED" ? "Announced" : option === "IN_CINEMAS" ? "In Cinemas" : "Released"}
                      </button>
                    ))}
                  </div>
                  {admin && (
                    <MoreActionsMenu
                      actions={[
                        {
                          label: "Delete Movie",
                          icon: <Trash size={14} />,
                          destructive: true,
                          onClick: () => setDeleteOpen(true),
                        },
                      ]}
                    />
                  )}
                </div>
              ) : (
                <>
                  <div style={{ marginTop: 14, display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
                    <QualityProfileDropdown profiles={profiles} activeProfile={activeProfile} onSelect={setQualityProfile} />
                    <SeriesMonitoringDropdown onSelect={(mode) => setSeriesMonitoring(mode)} />
                    {admin && (
                      <MoreActionsMenu
                        actions={[
                          {
                            label: kind === "show" ? "Delete Show" : "Delete Anime",
                            icon: <Trash size={14} />,
                            destructive: true,
                            onClick: () => setDeleteOpen(true),
                          },
                        ]}
                      />
                    )}
                    {kind === "show" && (
                      <label
                        style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13 }}
                        title="Off puts every episode straight in the series folder, skipping the Season N subfolder"
                      >
                        <Toggle
                          on={(ownedMedia as ShowDetail).seasonFolderEnabled !== false}
                          onChange={(next) => setSeasonFolderEnabled(next ? null : false)}
                        />
                        Season folders
                      </label>
                    )}
                  </div>
                  <p className="text-faint" style={{ fontSize: 11.5, marginTop: 8 }}>
                    {kind === "show"
                      ? activeProfile
                        ? `Kosmos checks every enabled indexer against "${activeProfile.name}" every 6 hours, per missing episode.`
                        : "Assign a quality profile to let Kosmos search for missing episodes automatically."
                      : "Automatic search runs every 6 hours against a monitored episode's absolute number — matching into a batch release isn't supported yet, so a fansub group's single-episode releases are what gets found."}
                  </p>
                </>
              )
            ) : (
              <button
                type="button"
                className="btn btn-hero"
                style={kind === "movie" ? { alignSelf: "flex-start" } : { marginTop: 14 }}
                disabled={addState !== "idle"}
                onClick={async () => {
                  if (!preview) return;
                  const createdId = await triggerAdd({
                    externalId: preview.externalId,
                    title: preview.title,
                    year: preview.year,
                    overview: preview.overview,
                    posterPath: preview.posterPath,
                    backdropPath: preview.backdropPath,
                    mediaType: mediaTypeFor(kind),
                  });
                  if (createdId) navigate(ownedPathFor(kind, createdId));
                }}
              >
                {addState === "adding" ? (
                  <Spinner size={16} className="spin" />
                ) : addState === "added" ? (
                  <CheckCircle size={16} weight="fill" />
                ) : (
                  <Plus size={16} weight="bold" />
                )}
                {addLabel}
              </button>
            )}

            {kind === "movie" && owned && (
              <FileStatusCard file={file} addedAt={(ownedMedia as Movie).addedAt} activeProfile={activeProfile} />
            )}
          </div>
        </div>
      </div>

      <div className="page">
        <div className="detail-info-grid">
          <div>
            <div className="section-label">Synopsis</div>
            {overview && <p className="detail-synopsis">{overview}</p>}
          </div>
          {facts.length > 0 && (
            <div>
              <div className="section-label">Details</div>
              <div className="fact-list">
                {facts.map((f) => (
                  <div key={f.k} className="fact-list-row">
                    <span className="k">{f.k}</span>
                    <span className="v">{f.v}</span>
                  </div>
                ))}
                {collection && (
                  <div className="fact-list-row">
                    <span className="k">Collection</span>
                    <span className="v">
                      <Link to={`/collections/${collection.externalId}`}>{collection.name}</Link>
                    </span>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>

        {(kind === "show" || kind === "anime") && groupedSeasons && groupedSeasons.length > 0 && (
          <div style={{ marginTop: 38 }}>
            <div className="section-label" style={{ marginBottom: 12 }}>
              Seasons
            </div>
            <GroupedEpisodeList
              seasons={groupedSeasons}
              onToggleEpisodeMonitored={owned ? (episodeId, next) => setEpisodeMonitored(episodeId, next) : undefined}
              onToggleSeasonMonitored={owned ? (seasonNumber, next) => setSeriesMonitoring(next ? "ALL" : "NONE", seasonNumber) : undefined}
            />
          </div>
        )}

        {kind !== "anime" && <CastRow cast={cast} />}
        <SimilarRow items={similar} />
      </div>

      {deleteOpen && (
        <ConfirmDialog
          title={`Delete "${title}"?`}
          body="This removes it from Kosmos permanently, including its match history and any requests tied to it."
          confirmLabel="Delete"
          extra={
            <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, marginTop: 4 }}>
              <Toggle on={deleteFiles} onChange={setDeleteFiles} />
              Also delete the file{kind === "movie" ? "" : "s"} from disk
            </label>
          }
          onConfirm={confirmDelete}
          onCancel={() => setDeleteOpen(false)}
        />
      )}
    </div>
  );
}

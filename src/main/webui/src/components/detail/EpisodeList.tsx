import {
  CaretDownIcon as CaretDown,
  CaretUpIcon as CaretUp,
  EyeIcon as Eye,
  EyeSlashIcon as EyeSlash,
  MagnifyingGlassIcon as MagnifyingGlass,
} from "@phosphor-icons/react";
import { useState } from "react";
import { Link } from "react-router-dom";
import type { EpisodeStatus } from "../../api/types";

const STATUS_DOT: Record<EpisodeStatus, string> = {
  MISSING: "dot-bad",
  GRABBED: "dot-warn",
  FAILED: "dot-bad",
  IMPORTED: "dot-warn",
  AVAILABLE: "dot-good",
};
const STATUS_LABEL: Record<EpisodeStatus, string> = {
  MISSING: "Missing",
  GRABBED: "Grabbed",
  FAILED: "Failed",
  IMPORTED: "Importing",
  AVAILABLE: "Available",
};

/** One row shared by every season's episode list below (shows and anime alike). */
export interface EpisodeRowData {
  id: string;
  number: number | null;
  /** Anime's absolute (franchise-continuous) number — shown as "01(26)" when it differs from
   * {@link number}; omitted or equal for shows, which have no separate absolute numbering. */
  absoluteNumber?: number | null;
  title: string;
  airDate: string | null;
  monitored: boolean;
  status: EpisodeStatus;
  searchHref: string | null;
}

function episodeNumberLabel(episode: EpisodeRowData): string {
  if (episode.number == null) return "—";
  const primary = String(episode.number).padStart(2, "0");
  if (episode.absoluteNumber == null || episode.absoluteNumber === episode.number) return primary;
  return `${primary}(${String(episode.absoluteNumber).padStart(2, "0")})`;
}

function EpisodeRow({
  episode,
  borderBottom,
  onToggleMonitored,
}: {
  episode: EpisodeRowData;
  borderBottom?: boolean;
  onToggleMonitored?: (episodeId: string, next: boolean) => void;
}) {
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: "26px 64px 1fr auto 90px 32px",
        gap: 14,
        alignItems: "center",
        padding: "8px 4px",
        fontSize: 12.5,
        borderBottom: borderBottom ? "1px solid var(--border)" : undefined,
        opacity: episode.monitored ? 1 : 0.55,
      }}
    >
      {onToggleMonitored ? (
        <button
          type="button"
          className="btn-icon"
          style={{ width: 22, height: 22 }}
          title={episode.monitored ? "Monitored — click to unmonitor" : "Not monitored — click to monitor"}
          onClick={() => onToggleMonitored(episode.id, !episode.monitored)}
        >
          {episode.monitored ? <Eye size={13} /> : <EyeSlash size={13} className="text-faint" />}
        </button>
      ) : (
        <span />
      )}
      <span className="text-faint" style={{ fontFamily: "var(--font-mono)" }}>
        {episodeNumberLabel(episode)}
      </span>
      <span>{episode.title}</span>
      <span className="status-tag" style={{ fontSize: 10.5 }}>
        <span className={`dot ${STATUS_DOT[episode.status]}`} />
        {STATUS_LABEL[episode.status]}
      </span>
      <span className="text-faint" style={{ fontSize: 11, textAlign: "right" }}>
        {episode.airDate ?? "—"}
      </span>
      {episode.searchHref ? (
        <Link to={episode.searchHref} className="btn btn-icon" title="Interactive search" style={{ width: 26, height: 26 }}>
          <MagnifyingGlass size={12} />
        </Link>
      ) : (
        <span />
      )}
    </div>
  );
}

export interface SeasonRowData {
  id: string;
  seasonNumber: number;
  name: string;
  episodeCount: number | null;
  episodes: EpisodeRowData[];
}

function SeasonRow({
  season,
  open,
  onToggle,
  onToggleEpisodeMonitored,
  onToggleSeasonMonitored,
}: {
  season: SeasonRowData;
  open: boolean;
  onToggle: () => void;
  onToggleEpisodeMonitored?: (episodeId: string, next: boolean) => void;
  onToggleSeasonMonitored?: (seasonNumber: number, next: boolean) => void;
}) {
  const allMonitored = season.episodes.length > 0 && season.episodes.every((e) => e.monitored);
  return (
    <div style={{ borderBottom: "1px solid var(--border)" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "13px 4px" }}>
        {onToggleSeasonMonitored && (
          <button
            type="button"
            className="btn-icon"
            style={{ width: 22, height: 22, flex: "none" }}
            title={allMonitored ? "Unmonitor this season" : "Monitor this season"}
            onClick={() => onToggleSeasonMonitored(season.seasonNumber, !allMonitored)}
          >
            {allMonitored ? <Eye size={13} /> : <EyeSlash size={13} className="text-faint" />}
          </button>
        )}
        <div onClick={onToggle} style={{ display: "flex", alignItems: "center", gap: 12, flex: 1, cursor: "pointer" }}>
          <span style={{ fontWeight: 500, fontSize: 13.5, flex: 1 }}>{season.name}</span>
          <span className="text-faint" style={{ fontSize: 11.5 }}>
            {season.episodeCount ?? season.episodes.length} episodes
          </span>
          {open ? <CaretUp size={13} className="text-faint" /> : <CaretDown size={13} className="text-faint" />}
        </div>
      </div>

      {open && (
        <div style={{ paddingBottom: 12 }}>
          {season.episodes.map((ep) => (
            <EpisodeRow key={ep.id} episode={ep} onToggleMonitored={onToggleEpisodeMonitored} />
          ))}
          {season.episodes.length === 0 && (
            <p className="text-muted" style={{ fontSize: 12, padding: "4px" }}>
              No episode data.
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/** A show's Season > Episode grouping, each season collapsed until clicked. */
export function GroupedEpisodeList({
  seasons,
  onToggleEpisodeMonitored,
  onToggleSeasonMonitored,
}: {
  seasons: SeasonRowData[];
  /** Omit on a not-yet-owned preview — nothing to toggle for a title that isn't in the library. */
  onToggleEpisodeMonitored?: (episodeId: string, next: boolean) => void;
  onToggleSeasonMonitored?: (seasonNumber: number, next: boolean) => void;
}) {
  const [openSeasonId, setOpenSeasonId] = useState<string | null>(null);
  return (
    <>
      {seasons.map((season) => (
        <SeasonRow
          key={season.id}
          season={season}
          open={openSeasonId === season.id}
          onToggle={() => setOpenSeasonId((s) => (s === season.id ? null : season.id))}
          onToggleEpisodeMonitored={onToggleEpisodeMonitored}
          onToggleSeasonMonitored={onToggleSeasonMonitored}
        />
      ))}
    </>
  );
}

import {
  CalendarBlankIcon as CalendarBlank,
  CaretDownIcon as CaretDown,
  CheckIcon as Check,
  ClockIcon as Clock,
  DotsThreeIcon as DotsThree,
  FolderOpenIcon as FolderOpen,
  ListBulletsIcon as ListBullets,
  MagnifyingGlassIcon as MagnifyingGlass,
  MedalIcon as Medal,
  PlanetIcon as Planet,
  SortAscendingIcon as SortAscending,
  SortDescendingIcon as SortDescending,
  SquaresFourIcon as SquaresFour,
  TextAaIcon as TextAa,
  TrashIcon as Trash,
} from "@phosphor-icons/react";
import { useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api } from "../api/client";
import type { Anime, Movie, Show } from "../api/types";
import { posterUrl } from "../api/tmdbImage";
import { MediaCard, STATUS_DOT_CLASS, STATUS_LABEL, type MediaStatus } from "../components/MediaCard";
import { NeedsReviewPanel } from "../components/NeedsReviewPanel";
import { useApi } from "../hooks/useApi";
import { tonalGradient } from "../utils/tonalGradient";

type View = "grid" | "list";
type SortKey = "added" | "title" | "year" | "quality";
type LibraryStatus = MediaStatus;
type LibraryKind = "movie" | "show" | "anime";

/** Fields every library-page source (Movie/Show/Anime) has in common — all {@link toLibraryItem} needs. */
interface LibrarySourceItem {
  id: string;
  title: string;
  year: number | null;
  posterPath: string | null;
  addedAt: string;
  partiallyAvailable?: boolean;
}

const KIND_CONFIG: Record<
  LibraryKind,
  { title: string; noun: string; basePath: string; fetch: () => Promise<LibrarySourceItem[]> }
> = {
  movie: { title: "Movies", noun: "movies", basePath: "/movies", fetch: () => api.listMovies() as Promise<Movie[]> },
  show: { title: "Series", noun: "series", basePath: "/shows", fetch: () => api.listShows() as Promise<Show[]> },
  anime: { title: "Anime", noun: "anime", basePath: "/anime", fetch: () => api.listAnime() as Promise<Anime[]> },
};

/**
 * The real Movie/Show/Anime APIs (api/types.ts) have no file-size or monitoring/download-status
 * fields yet, and every row that exists in the DB was added via the metadata-search flow — so a
 * row is either fully on disk or, for shows/anime, missing some episode files
 * ({@link LibrarySourceItem#partiallyAvailable}, from {@link MediaAvailabilityService}).
 * "downloading"/"missing" stay in the type and the row/card rendering for design fidelity, but no
 * real row can currently produce them.
 */
interface LibraryItem {
  id: string;
  title: string;
  year: number | null;
  posterPath: string | null;
  quality: string;
  sizeGb: number | null;
  addedDaysAgo: number;
  status: LibraryStatus;
  progress?: number;
  tone: number;
}

function hashTone(id: string): number {
  let h = 0;
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0;
  return Math.abs(h);
}

function toLibraryItem(item: LibrarySourceItem): LibraryItem {
  const addedMs = Date.now() - new Date(item.addedAt).getTime();
  return {
    id: item.id,
    title: item.title,
    year: item.year,
    posterPath: item.posterPath,
    quality: "—",
    sizeGb: null,
    addedDaysAgo: Math.max(0, Math.floor(addedMs / (1000 * 60 * 60 * 24))),
    status: item.partiallyAvailable ? "partially-available" : "in-library",
    tone: hashTone(item.id),
  };
}

const SORTS: { key: SortKey; label: string; icon: typeof Clock }[] = [
  { key: "added", label: "Added date", icon: Clock },
  { key: "title", label: "Title", icon: TextAa },
  { key: "year", label: "Year", icon: CalendarBlank },
  { key: "quality", label: "Quality", icon: Medal },
];

/** Order for the filter chips; label/dot color come from MediaCard's shared status maps so this
 * can't drift out of sync with how the cards themselves render a status. */
const FILTER_ORDER: LibraryStatus[] = ["missing", "in-library", "partially-available", "downloading"];
const FILTERS: { status: LibraryStatus; label: string; dotClass: string }[] = FILTER_ORDER.map(
  (status) => ({ status, label: status === "in-library" ? "Downloaded" : STATUS_LABEL[status], dotClass: STATUS_DOT_CLASS[status] }),
);

/** List-view status tag color — everything else about a status comes from MediaCard's shared maps. */
const TAG_CLASS: Record<LibraryStatus, string> = {
  "in-library": "lib-good",
  "partially-available": "lib-warn",
  downloading: "lib-warn",
  missing: "lib-bad",
};

function qualityRank(quality: string): number {
  if (quality.startsWith("2160p Remux")) return 4;
  if (quality.startsWith("2160p")) return 3;
  if (quality.startsWith("1080p")) return 2;
  if (quality === "—") return -1;
  return 0;
}

function LibraryGridCard({ item, basePath }: { item: LibraryItem; basePath: string }) {
  return (
    <MediaCard
      to={`${basePath}/${item.id}`}
      title={item.title}
      year={item.year}
      posterPath={item.posterPath}
      mediaItemId={item.id}
      status={item.status}
      progress={item.status === "downloading" ? item.progress : undefined}
      placeholderBackground={tonalGradient(item.tone)}
    />
  );
}

function LibraryListRow({ item, basePath }: { item: LibraryItem; basePath: string }) {
  const src = posterUrl(item.posterPath);
  const subLabel =
    item.status === "in-library"
      ? "on disk"
      : item.status === "partially-available"
        ? "some episodes on disk"
        : item.status === "downloading"
          ? `grabbing from indexer · ${item.progress}%`
          : "monitored · no release found";

  return (
    <Link to={`${basePath}/${item.id}`} className="library-list-row">
      <div className="library-list-title-cell">
        {src ? (
          <img className="library-list-thumb" src={src} alt="" loading="lazy" />
        ) : (
          <div className="library-list-thumb" style={{ background: tonalGradient(item.tone) }} />
        )}
        <div className="library-list-title-main">
          <div className="title">{item.title}</div>
          <div className="sub">{subLabel}</div>
        </div>
      </div>
      <span className="library-list-mono">{item.year ?? "—"}</span>
      <span className={`library-list-mono${item.quality === "—" ? " dim" : ""}`}>{item.quality}</span>
      <span className="library-list-mono">{item.sizeGb != null ? `${item.sizeGb.toFixed(1)} GB` : "—"}</span>
      <span className={`status-tag ${TAG_CLASS[item.status]}`}>
        <span className={`dot ${STATUS_DOT_CLASS[item.status]}`} />
        {STATUS_LABEL[item.status]}
      </span>
      <div className="library-list-actions">
        <button type="button" className="btn btn-icon" onClick={(e) => e.preventDefault()} title="Search">
          <MagnifyingGlass size={14} />
        </button>
        <button type="button" className="btn btn-icon" onClick={(e) => e.preventDefault()} title="Remove">
          <Trash size={14} />
        </button>
        <button type="button" className="btn btn-icon" onClick={(e) => e.preventDefault()} title="More">
          <DotsThree size={16} />
        </button>
      </div>
    </Link>
  );
}

function EmptyLibrary({ noun }: { noun: string }) {
  return (
    <div className="empty-state">
      <div className="empty-state-inner">
        <div style={{ position: "relative", width: 150, height: 112, margin: "0 auto 30px" }}>
          <div
            style={{
              position: "absolute",
              left: 6,
              top: 12,
              width: 58,
              aspectRatio: "2 / 3",
              borderRadius: 10,
              background: "#14151f",
              border: "1px dashed var(--border-strong)",
              transform: "rotate(-8deg)",
            }}
          />
          <div
            style={{
              position: "absolute",
              right: 6,
              top: 12,
              width: 58,
              aspectRatio: "2 / 3",
              borderRadius: 10,
              background: "#14151f",
              border: "1px dashed var(--border-strong)",
              transform: "rotate(8deg)",
            }}
          />
          <div
            style={{
              position: "absolute",
              left: "50%",
              top: 0,
              transform: "translateX(-50%)",
              width: 62,
              aspectRatio: "2 / 3",
              borderRadius: 11,
              background: "var(--bg)",
              border: "1px solid rgba(145,132,217,.28)",
              display: "grid",
              placeItems: "center",
              boxShadow: "0 16px 36px rgba(0,0,0,.5)",
            }}
          >
            <Planet size={22} color="var(--accent-tint)" />
          </div>
        </div>
        <div className="empty-state-title">Your library is empty</div>
        <p className="empty-state-body">
          First, tell Kosmos where your media lives — then add a movie, series, or anime from Discover or
          Search and it'll land there once it's on disk.
        </p>
        <div className="empty-state-actions">
          <Link to="/settings/root-folders" className="btn btn-hero">
            <FolderOpen size={15} />
            Add a Media Folder
          </Link>
          <Link to="/" className="btn btn-secondary">
            <SquaresFour size={15} />
            Discover {noun}
          </Link>
        </div>
        <p className="text-faint" style={{ marginTop: 20, fontSize: 12 }}>
          Already have a folder full of media? <Link to="/import">Match it against your library</Link>, or import
          it straight from <Link to="/settings/jellyfin">Jellyfin</Link>,{" "}
          <Link to="/settings/radarr">Radarr</Link>, or <Link to="/settings/sonarr">Sonarr</Link>.
        </p>
      </div>
    </div>
  );
}

export default function LibraryPage() {
  const [searchParams] = useSearchParams();
  const typeParam = searchParams.get("type");
  const isReview = typeParam === "review";
  const kind: LibraryKind = typeParam === "show" || typeParam === "anime" ? typeParam : "movie";
  const config = KIND_CONFIG[kind];

  // Every hook below still runs on the Needs Review tab (same mounted component, hook order must
  // stay constant across a query-param-only navigation) — the fetch itself is just skipped since
  // NeedsReviewPanel owns its own data.
  const { data: rawItems, loading, error: loadError } = useApi(
    () => (isReview ? Promise.resolve([]) : config.fetch()),
    [kind, isReview],
  );
  const [view, setView] = useState<View>("grid");
  const [filterText, setFilterText] = useState("");
  const [statusFilters, setStatusFilters] = useState<Set<LibraryStatus>>(new Set());
  const [sort, setSort] = useState<SortKey>("title");
  const [sortOpen, setSortOpen] = useState(false);
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  const libraryItems = useMemo(() => (rawItems ?? []).map(toLibraryItem), [rawItems]);

  const counts: Record<LibraryStatus, number> = {
    "in-library": libraryItems.filter((m) => m.status === "in-library").length,
    "partially-available": libraryItems.filter((m) => m.status === "partially-available").length,
    downloading: libraryItems.filter((m) => m.status === "downloading").length,
    missing: libraryItems.filter((m) => m.status === "missing").length,
  };

  const filtered = useMemo(() => {
    let rows = libraryItems;
    if (filterText.trim()) {
      const q = filterText.trim().toLowerCase();
      rows = rows.filter((m) => m.title.toLowerCase().includes(q));
    }
    if (statusFilters.size > 0) {
      rows = rows.filter((m) => statusFilters.has(m.status));
    }
    const sorted = [...rows];
    switch (sort) {
      case "title":
        sorted.sort((a, b) => a.title.localeCompare(b.title));
        break;
      case "year":
        sorted.sort((a, b) => (b.year ?? 0) - (a.year ?? 0));
        break;
      case "quality":
        sorted.sort((a, b) => qualityRank(b.quality) - qualityRank(a.quality));
        break;
      default:
        sorted.sort((a, b) => a.addedDaysAgo - b.addedDaysAgo);
    }
    if (sortDir === "desc") {
      sorted.reverse();
    }
    return sorted;
  }, [libraryItems, filterText, statusFilters, sort, sortDir]);

  const isFiltered = filterText.trim() !== "" || statusFilters.size > 0;
  const showEmpty = !loading && (isFiltered ? filtered.length === 0 : libraryItems.length === 0);

  function toggleFilter(status: LibraryStatus) {
    setStatusFilters((prev) => {
      const next = new Set(prev);
      if (next.has(status)) next.delete(status);
      else next.add(status);
      return next;
    });
  }

  if (isReview) {
    return (
      <div className="page">
        <div className="page-header">
          <h1>Needs Review</h1>
        </div>
        <NeedsReviewPanel />
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1>{config.title}</h1>
        <span className="page-count">
          {isFiltered
            ? `${filtered.length} of ${libraryItems.length} ${config.noun}`
            : `${libraryItems.length} ${config.noun}`}
        </span>
        {(kind === "show" || kind === "anime") && (
          <Link to="/season-pass" className="btn btn-secondary" style={{ marginLeft: "auto" }}>
            Season Pass
          </Link>
        )}
      </div>

      {loadError && <p className="text-muted">Failed to load library: {loadError}</p>}

      <div className="library-toolbar">
        <input
          className="input library-filter-input"
          value={filterText}
          onChange={(e) => setFilterText(e.target.value)}
          placeholder="Filter this library…"
        />

        {FILTERS.map((f) => (
          <button
            key={f.status}
            type="button"
            className={`chip-filter${statusFilters.has(f.status) ? " active" : ""}`}
            onClick={() => toggleFilter(f.status)}
          >
            <span className={`dot ${f.dotClass}`} />
            {f.label}
            <span className="chip-filter-count">{counts[f.status]}</span>
          </button>
        ))}

        <div className="library-toolbar-spacer" />

        <div className="sort-dropdown">
          <button type="button" className="btn btn-secondary" onClick={() => setSortOpen((v) => !v)}>
            <SortAscending size={14} />
            {SORTS.find((s) => s.key === sort)?.label}
            <CaretDown size={11} />
          </button>
          {sortOpen && (
            <div className="sort-menu" onMouseLeave={() => setSortOpen(false)}>
              {SORTS.map((s) => (
                <button
                  key={s.key}
                  type="button"
                  className="sort-menu-item"
                  onClick={() => {
                    setSort(s.key);
                    setSortOpen(false);
                  }}
                >
                  <s.icon size={15} />
                  {s.label}
                  {sort === s.key && <Check size={13} weight="bold" className="check" />}
                </button>
              ))}
            </div>
          )}
        </div>

        <button
          type="button"
          className="btn btn-icon"
          onClick={() => setSortDir((d) => (d === "asc" ? "desc" : "asc"))}
          title={sortDir === "asc" ? "Ascending" : "Descending"}
          aria-label="Toggle sort direction"
        >
          {sortDir === "asc" ? <SortAscending size={14} /> : <SortDescending size={14} />}
        </button>

        <div className="seg">
          <button className={view === "grid" ? "active" : ""} onClick={() => setView("grid")} aria-label="Grid view">
            <SquaresFour size={14} />
            Grid
          </button>
          <button className={view === "list" ? "active" : ""} onClick={() => setView("list")} aria-label="List view">
            <ListBullets size={14} />
            List
          </button>
        </div>
      </div>

      {showEmpty ? (
        <EmptyLibrary noun={config.title} />
      ) : view === "grid" ? (
        <div className="poster-grid">
          {filtered.map((item) => (
            <LibraryGridCard key={item.id} item={item} basePath={config.basePath} />
          ))}
        </div>
      ) : (
        <div className="library-list">
          <div className="library-list-header">
            <span>Title</span>
            <span>Year</span>
            <span>Quality</span>
            <span>Size</span>
            <span>Status</span>
            <span>Actions</span>
          </div>
          {filtered.map((item) => (
            <LibraryListRow key={item.id} item={item} basePath={config.basePath} />
          ))}
        </div>
      )}
    </div>
  );
}

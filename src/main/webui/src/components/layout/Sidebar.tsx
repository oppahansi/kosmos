import { CaretDoubleLeftIcon as CaretDoubleLeft, CaretDoubleRightIcon as CaretDoubleRight } from "@phosphor-icons/react";
import { useEffect, useState } from "react";
import { Link, NavLink, useLocation } from "react-router-dom";
import { api } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";
import { useActiveGrabCount } from "../../hooks/useActiveGrabCount";
import { useApi } from "../../hooks/useApi";
import { useLibraryChanges } from "../../hooks/useLibraryChanges";
import { formatTb } from "../../utils/formatBytes";
import { navItems } from "./navItems";

const CHANGE_CONTENT_TYPES = ["movie", "show", "anime"];

/** A LibraryChangeEvent (see useLibraryChanges) reloads stats immediately in the common case; this
 * poll is now only a safety net for a dropped/missed SSE connection, hence the long interval. */
const LIBRARY_STATS_REFRESH_MS = 120_000;

/** Collections sidebar — no smart-collections feature exists yet (see LibraryResource.stats), just these real content types, each routing to LibraryPage filtered by `?type=`. */
const COLLECTIONS: { label: string; type: "movie" | "show" | "anime" | "review"; tint: string }[] = [
  { label: "Movies", type: "movie", tint: "#9184D9" },
  { label: "Series", type: "show", tint: "#5AC8DC" },
  { label: "Anime", type: "anime", tint: "#7FD6AC" },
  { label: "Needs Review", type: "review", tint: "#E0A75E" },
];

export function Sidebar() {
  const [collapsed, setCollapsed] = useState(false);
  const location = useLocation();
  const showCollections = location.pathname.startsWith("/library");
  const activeType = new URLSearchParams(location.search).get("type") ?? "movie";
  const showDiskFree = location.pathname.startsWith("/activity");
  const { user } = useAuth();
  const { data: requests } = useApi(() => api.listRequests(), [user?.id]);
  const { data: stats, reload: reloadStats } = useApi(() => api.libraryStats(), []);
  const activeJobCount = useActiveGrabCount();

  useLibraryChanges(CHANGE_CONTENT_TYPES, () => reloadStats());

  useEffect(() => {
    const id = window.setInterval(reloadStats, LIBRARY_STATS_REFRESH_MS);
    return () => window.clearInterval(id);
  }, [reloadStats]);

  const pendingRequestCount = requests?.filter((r) => r.status === "PENDING").length ?? 0;
  const libraryCount = stats ? stats.movieCount + stats.seriesCount + stats.animeCount : null;

  const navCounts: Record<string, string> = {
    "/library": libraryCount != null ? libraryCount.toLocaleString() : "",
    "/requests": String(pendingRequestCount),
    "/activity": String(activeJobCount),
  };

  const collectionCounts: Record<"movie" | "show" | "anime" | "review", number> = {
    movie: stats?.movieCount ?? 0,
    show: stats?.seriesCount ?? 0,
    anime: stats?.animeCount ?? 0,
    review: stats?.needsReviewCount ?? 0,
  };
  const storageUsedPct =
    stats?.totalBytes && stats.totalBytes > 0 ? Math.round((stats.usedBytes / stats.totalBytes) * 100) : 0;
  const storageUsedLabel = stats
    ? stats.totalBytes != null
      ? `${formatTb(stats.usedBytes)} of ${formatTb(stats.totalBytes)} used`
      : `${formatTb(stats.usedBytes)} used`
    : "";
  const storageFreeLabel =
    stats?.totalBytes != null ? `${formatTb(stats.totalBytes - stats.usedBytes)} free` : "";

  return (
    <aside className={`sidebar${collapsed ? " collapsed" : ""}`}>
      <div className="sidebar-brand">
        <span className="sidebar-mark" aria-hidden />
        <span className="sidebar-wordmark">Kosmos</span>
      </div>

      <nav>
        {navItems.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            end={to === "/"}
            className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}
          >
            <span className="nav-item-icon">
              <Icon size={18} weight="regular" />
            </span>
            <span className="nav-item-label">{label}</span>
            {navCounts[to] && navCounts[to] !== "0" && <span className="nav-item-count">{navCounts[to]}</span>}
          </NavLink>
        ))}
      </nav>

      {showCollections && (
        <>
          <div className="sidebar-section-label">Collections</div>
          {COLLECTIONS.filter((c) => c.type !== "review" || collectionCounts.review > 0).map((c) => (
            <Link
              key={c.type}
              to={`/library?type=${c.type}`}
              className={`sidebar-collection${activeType === c.type ? " active" : ""}`}
            >
              <span className="sidebar-collection-dot" style={{ background: c.tint }} />
              <span className="sidebar-collection-label">{c.label}</span>
              <span className="sidebar-collection-count">{collectionCounts[c.type]}</span>
            </Link>
          ))}
        </>
      )}

      <div className="sidebar-spacer" />

      {showCollections && (
        <div className="sidebar-storage">
          <span className="dot dot-good" />
          <span className="sidebar-storage-text">{storageUsedLabel}</span>
        </div>
      )}

      {showDiskFree && (
        <div className="sidebar-disk">
          <div className="sidebar-disk-row">
            <span className="sidebar-disk-label">Disk</span>
            <span className="sidebar-disk-value">{storageFreeLabel}</span>
          </div>
          <div className="progress-track">
            <div className="progress-fill sidebar-disk-fill" style={{ width: `${storageUsedPct}%` }} />
          </div>
        </div>
      )}

      <button type="button" className="sidebar-toggle" onClick={() => setCollapsed((c) => !c)}>
        {collapsed ? <CaretDoubleRight size={16} /> : <CaretDoubleLeft size={16} />}
        <span className="sidebar-toggle-label">Collapse</span>
      </button>

      <div className="sidebar-status">
        <span className="dot dot-good" />
        <span className="sidebar-status-text">
          Server healthy{activeJobCount > 0 ? ` · ${activeJobCount} jobs` : ""}
        </span>
      </div>
    </aside>
  );
}

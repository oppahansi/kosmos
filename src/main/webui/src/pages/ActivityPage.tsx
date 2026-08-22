import {
  ArrowDownIcon as ArrowDown,
  ArrowFatLineUpIcon as ArrowFatLineUp,
  CheckIcon as Check,
  ClockCounterClockwiseIcon as ClockCounterClockwise,
  FolderOpenIcon as FolderOpen,
  MagnifyingGlassIcon as MagnifyingGlass,
  TrashIcon as Trash,
  XIcon as X,
} from "@phosphor-icons/react";
import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, ApiError } from "../api/client";
import type { ActivityHistoryItem, Grab } from "../api/types";
import { useApi } from "../hooks/useApi";
import { formatBytes } from "../utils/formatBytes";
import { relativeTime } from "../utils/relativeTime";
import { tonalGradient } from "../utils/tonalGradient";

type HistoryFilter = "All" | "Grabbed" | "Imported" | "Failed";
const HISTORY_FILTERS: HistoryFilter[] = ["All", "Grabbed", "Imported", "Failed"];
const HISTORY_FILTER_KIND: Record<HistoryFilter, ActivityHistoryItem["kind"] | null> = {
  All: null,
  Grabbed: "GRABBED",
  Imported: "IMPORTED",
  Failed: "FAILED",
};

const EVENT_META: Record<
  ActivityHistoryItem["kind"],
  { icon: typeof Check; fg: string; bg: string }
> = {
  IMPORTED: { icon: Check, fg: "var(--status-good-text)", bg: "rgba(79,191,139,.14)" },
  UPGRADED: { icon: ArrowFatLineUp, fg: "var(--accent-tint)", bg: "rgba(145,132,217,.16)" },
  GRABBED: { icon: ArrowDown, fg: "var(--status-warn-text)", bg: "rgba(224,169,74,.14)" },
  FAILED: { icon: X, fg: "var(--status-bad-text)", bg: "rgba(224,104,95,.14)" },
  FILE_DELETED: { icon: Trash, fg: "var(--status-bad-text)", bg: "rgba(224,104,95,.14)" },
  RENAMED: { icon: Check, fg: "var(--text-muted)", bg: "rgba(233,233,237,.08)" },
};

function dayLabel(iso: string): string {
  const date = new Date(iso);
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  const sameDay = (a: Date, b: Date) =>
    a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  if (sameDay(date, today)) return "Today";
  if (sameDay(date, yesterday)) return "Yesterday";
  return date.toLocaleDateString(undefined, { month: "long", day: "numeric" });
}

export default function ActivityPage() {
  const { data: grabs, reload: reloadGrabs } = useApi(() => api.listGrabs(), []);
  const { data: history, reload: reloadHistory } = useApi(() => api.listActivityHistory(200), []);
  const { data: activityStats, reload: reloadStats } = useApi(() => api.activityStats(), []);
  const [histFilter, setHistFilter] = useState<HistoryFilter>("All");
  const [markingFailed, setMarkingFailed] = useState<string | null>(null);
  const [searchingMissing, setSearchingMissing] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  // Live progress only changes server-side (the download client itself), so poll rather than
  // relying on a local tick — GrabsResource already fetches current progress on every call.
  useEffect(() => {
    const id = setInterval(reloadGrabs, 5000);
    return () => clearInterval(id);
  }, [reloadGrabs]);

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast((current) => (current === message ? null : current)), 6000);
  }

  const queue = useMemo(() => grabs?.filter((g) => g.status === "GRABBED") ?? [], [grabs]);
  const isEmpty = queue.length === 0;

  const importedToday = activityStats?.importedToday ?? 0;
  const failedToday = activityStats?.failedToday ?? 0;

  const groups = useMemo(() => {
    const kind = HISTORY_FILTER_KIND[histFilter];
    const filtered = (history ?? []).filter((h) => !kind || h.kind === kind);
    const byLabel = new Map<string, ActivityHistoryItem[]>();
    for (const item of filtered) {
      const label = dayLabel(item.occurredAt);
      const rows = byLabel.get(label) ?? [];
      rows.push(item);
      byLabel.set(label, rows);
    }
    return Array.from(byLabel.entries()).map(([label, rows]) => ({ label, rows }));
  }, [histFilter, history]);

  async function markFailed(grab: Grab) {
    setMarkingFailed(grab.id);
    try {
      await api.markGrabFailed(grab.id);
      reloadGrabs();
      reloadHistory();
      reloadStats();
    } catch (e) {
      showToast(e instanceof ApiError ? `Could not mark failed: ${e.message}` : "Could not mark failed");
    } finally {
      setMarkingFailed(null);
    }
  }

  async function searchAllMissing() {
    setSearchingMissing(true);
    try {
      await Promise.all(
        ["automatic-search", "tv-automatic-search", "anime-automatic-search"].map((name) =>
          api.runJobNow(name).catch(() => null),
        ),
      );
      showToast("Automatic search started for every monitored title.");
    } finally {
      setSearchingMissing(false);
    }
  }

  const headline = isEmpty
    ? `queue clear · ${history?.length ?? 0} events in history`
    : `${queue.length} downloading`;

  const stats = [
    { label: "Queue", value: isEmpty ? "Empty" : `${queue.length} active`, dotClass: isEmpty ? "" : "dot-warn pulsing" },
    { label: "Imported today", value: `${importedToday} files`, dotClass: "dot-good" },
    { label: "Failed today", value: `${failedToday} grabs`, dotClass: failedToday > 0 ? "dot-bad" : "" },
  ];

  return (
    <div className="page with-top-padding">
      <div className="page-header" style={{ alignItems: "flex-start", justifyContent: "space-between", marginBottom: 16 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 16 }}>
          <h1>Activity</h1>
          <span className="text-faint" style={{ fontSize: 12.5 }}>
            {headline}
          </span>
        </div>
        <div style={{ display: "flex", gap: 10 }}>
          <Link to="/import" className="btn btn-secondary">
            <FolderOpen size={14} />
            Manual Import
          </Link>
        </div>
      </div>

      <div className="stat-grid" style={{ marginBottom: isEmpty ? 0 : 28 }}>
        {stats.map((s) => (
          <div className="stat-card" key={s.label}>
            <div className="stat-card-label">
              <span className={`dot ${s.dotClass}`} />
              {s.label.toUpperCase()}
            </div>
            <div className="stat-card-value">{s.value}</div>
          </div>
        ))}
      </div>

      {!isEmpty && (
        <div style={{ marginTop: 28, marginBottom: 8 }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 11, marginBottom: 14 }}>
            <h2 style={{ margin: 0, fontSize: 17, fontWeight: 500, letterSpacing: "-0.02em" }}>Downloading now</h2>
            <span className="text-faint" style={{ fontSize: 11.5 }}>
              {queue.length} in queue
            </span>
          </div>

          {queue.map((grab, i) => {
            const pct = grab.progressPercent;
            return (
              <div key={grab.id} className="download-row">
                <div
                  style={{
                    width: 46,
                    aspectRatio: "2 / 3",
                    borderRadius: 8,
                    overflow: "hidden",
                    flex: "none",
                    border: "1px solid var(--border-subtle)",
                    background: tonalGradient(i + 1),
                  }}
                />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 5 }}>
                    <span style={{ fontSize: 14.5, fontWeight: 500, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                      {grab.title}
                    </span>
                    <span className="tag-outline" style={{ padding: "3px 7px", fontSize: 10.5, fontFamily: "var(--font-mono)", flex: "none" }}>
                      {grab.downloadClientName}
                    </span>
                    <span
                      style={{
                        display: "inline-flex",
                        alignItems: "center",
                        gap: 5,
                        flex: "none",
                        fontSize: 10,
                        fontWeight: 600,
                        letterSpacing: "0.08em",
                        textTransform: "uppercase",
                        color: "var(--status-warn-text)",
                      }}
                    >
                      <span className="dot pulsing" style={{ background: "#E0A94A" }} />
                      Downloading
                    </span>
                  </div>
                  <div
                    style={{
                      fontFamily: "var(--font-mono)",
                      fontSize: 11,
                      color: "var(--text-faint)",
                      marginBottom: 9,
                    }}
                  >
                    grabbed {relativeTime(grab.grabbedAt)}
                  </div>
                  <div className="progress-track" style={{ marginBottom: 8 }}>
                    <div
                      className="progress-fill"
                      style={{
                        width: `${pct ?? 0}%`,
                        background: "var(--accent-gradient)",
                        transition: "width 900ms linear",
                      }}
                    />
                  </div>
                  <div className="download-stat-line">
                    <span style={{ color: "var(--accent-2)", fontWeight: 500 }}>
                      {pct != null ? `${pct.toFixed(1)}%` : "—"}
                    </span>
                  </div>
                </div>
                <div className="download-row-actions">
                  <button
                    type="button"
                    className="btn-icon"
                    aria-label="Mark failed"
                    title="Mark failed — blocklists this release and frees the title for another search"
                    disabled={markingFailed === grab.id}
                    onClick={() => markFailed(grab)}
                  >
                    <X size={15} />
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {isEmpty && (
        <div className="empty-state">
          <div className="empty-state-inner">
            <div
              style={{
                position: "relative",
                width: 140,
                height: 140,
                margin: "0 auto 30px",
                display: "grid",
                placeItems: "center",
              }}
            >
              <span style={{ position: "absolute", inset: 0, borderRadius: "50%", border: "1px dashed rgba(233,233,237,.1)" }} />
              <span style={{ position: "absolute", inset: 22, borderRadius: "50%", border: "1px dashed rgba(233,233,237,.09)" }} />
              <span
                style={{
                  position: "absolute",
                  inset: 44,
                  borderRadius: "50%",
                  border: "1px solid rgba(145,132,217,.24)",
                  background: "rgba(145,132,217,.07)",
                }}
              />
              <ArrowDown size={26} color="var(--accent-tint)" style={{ position: "relative" }} />
            </div>
            <div className="empty-state-title">Nothing downloading right now</div>
            <p className="empty-state-body">
              The queue is clear. Kosmos keeps watching your monitored titles and will start a grab the moment a release clears your
              quality profile.
            </p>
            <div className="empty-state-actions">
              <button type="button" className="btn btn-hero" disabled={searchingMissing} onClick={searchAllMissing}>
                <MagnifyingGlass size={15} />
                {searchingMissing ? "Searching…" : "Search all missing now"}
              </button>
              <a href="#history" className="btn btn-secondary">
                <ClockCounterClockwise size={15} />
                Jump to history
              </a>
            </div>
          </div>
        </div>
      )}

      <div id="history" style={{ marginTop: isEmpty ? 8 : 34 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 17, fontWeight: 500, letterSpacing: "-0.02em" }}>History</h2>
          <span className="text-faint" style={{ fontSize: 11.5 }}>
            {history?.length ?? 0} events
          </span>
          <div style={{ flex: 1 }} />
          <div className="filter-tabs">
            {HISTORY_FILTERS.map((f) => (
              <button key={f} className={histFilter === f ? "active" : ""} onClick={() => setHistFilter(f)}>
                {f}
              </button>
            ))}
          </div>
        </div>

        {groups.length === 0 && <p className="text-faint">No history yet.</p>}

        {groups.map((g) => (
          <div className="history-group" key={g.label}>
            <div className="history-group-label">{g.label}</div>
            <div className="history-table">
              {g.rows.map((h) => {
                const meta = EVENT_META[h.kind];
                const Icon = meta.icon;
                return (
                  <div className="history-row" key={h.id}>
                    <span className="history-row-icon" style={{ background: meta.bg, color: meta.fg }}>
                      <Icon size={14} />
                    </span>
                    <div style={{ minWidth: 0 }}>
                      <div className="history-row-title" style={{ color: h.kind === "FAILED" ? "var(--text-secondary)" : "var(--text-primary)" }}>
                        {h.title}
                      </div>
                      <div className="history-row-release">{h.message}</div>
                    </div>
                    <span style={{ fontFamily: "var(--font-mono)", fontSize: 11.5, color: meta.fg }}>{h.kind}</span>
                    <span className="text-faint" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
                      {h.sizeBytes ? formatBytes(h.sizeBytes) : ""}
                    </span>
                    <span className="text-disabled" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
                      {relativeTime(h.occurredAt)}
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </div>

      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}

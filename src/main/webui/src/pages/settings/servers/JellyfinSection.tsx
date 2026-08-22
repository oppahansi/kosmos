import {
  ArrowsClockwiseIcon as ArrowsClockwise,
  CheckIcon as Check,
  FilmSlateIcon as FilmSlate,
  PlusIcon as Plus,
  UsersIcon as Users,
} from "@phosphor-icons/react";
import { useState } from "react";
import { api, ApiError } from "../../../api/client";
import type { JellyfinServer, JobProgressEvent } from "../../../api/types";
import { JobProgressBar } from "../../../components/JobProgressBar";
import { AddServerModal } from "../../../components/settings/AddServerModal";
import { ServerRowHeader } from "../../../components/settings/ServerRowHeader";
import { useApi } from "../../../hooks/useApi";
import { useJobProgress } from "../../../hooks/useJobProgress";

export function JellyfinSection({ showToast }: { showToast: (message: string) => void }) {
  const { data: servers, error: loadError, setData: setServers, reload } = useApi(
    () => api.listJellyfinServers(),
    [],
  );
  const [modalOpen, setModalOpen] = useState(false);

  function patchServer(id: string, patch: Partial<JellyfinServer>) {
    setServers((current) => current?.map((s) => (s.id === id ? { ...s, ...patch } : s)) ?? current);
  }

  return (
    <div>
      <div className="page-header" style={{ padding: "0 0 4px" }}>
        <div style={{ flex: 1, minWidth: 280 }}>
          <h3 style={{ marginBottom: 6 }}>Jellyfin</h3>
          <p className="text-muted" style={{ maxWidth: "60ch" }}>
            Connect an already-scanned Jellyfin library. Library sync marks what you already have
            as available; user import brings in accounts to sign into Kosmos with — each runs (and
            can be scheduled) on its own, over just the libraries or users you pick.
          </p>
        </div>
        <button type="button" className="btn btn-hero" onClick={() => setModalOpen(true)}>
          <Plus size={15} weight="bold" />
          Add Server
        </button>
      </div>

      {loadError && <p className="text-muted">Failed to load Jellyfin servers: {loadError}</p>}
      {servers?.length === 0 && <p className="text-muted">No Jellyfin server connected yet.</p>}

      {servers?.map((server) => (
        <JellyfinServerRow
          key={server.id}
          server={server}
          onServerUpdate={(patch) => patchServer(server.id, patch)}
          showToast={showToast}
        />
      ))}

      {modalOpen && (
        <AddServerModal
          icon={<ArrowsClockwise size={16} />}
          title="Add Jellyfin server"
          helpText="Create an API key under Dashboard → API Keys in Jellyfin."
          namePlaceholder="Home Jellyfin"
          urlPlaceholder="http://192.168.1.10:8096"
          testConnection={api.testJellyfinConnection}
          createServer={api.createJellyfinServer}
          onClose={() => setModalOpen(false)}
          onCreated={() => {
            setModalOpen(false);
            reload();
            showToast("Jellyfin server added — pick its libraries and users below to sync.");
          }}
        />
      )}
    </div>
  );
}

function JellyfinServerRow({
  server,
  onServerUpdate,
  showToast,
}: {
  server: JellyfinServer;
  onServerUpdate: (patch: Partial<JellyfinServer>) => void;
  showToast: (message: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div
      className={`indexer-row${server.enabled ? "" : " disabled"}`}
      style={{ flexDirection: "column", alignItems: "stretch" }}
    >
      <ServerRowHeader
        icon={<ArrowsClockwise size={16} />}
        name={server.name}
        baseUrl={server.baseUrl}
        apiKeySet={server.apiKeySet}
        expanded={expanded}
        onToggle={() => setExpanded((v) => !v)}
      />

      {expanded && (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 18,
            marginTop: 16,
            paddingTop: 16,
            borderTop: "1px solid var(--border-subtle)",
          }}
        >
          <LibrarySelectionPanel server={server} onServerUpdate={onServerUpdate} showToast={showToast} />
          <UserSelectionPanel server={server} onServerUpdate={onServerUpdate} showToast={showToast} />
        </div>
      )}
    </div>
  );
}

function LibrarySelectionPanel({
  server,
  onServerUpdate,
  showToast,
}: {
  server: JellyfinServer;
  onServerUpdate: (patch: Partial<JellyfinServer>) => void;
  showToast: (message: string) => void;
}) {
  const { data: libraries, loading, error } = useApi(() => api.listJellyfinLibraries(server.id), [server.id]);
  const [selected, setSelected] = useState<Set<string>>(new Set(server.selectedLibraryIds));
  const [clicking, setClicking] = useState(false);
  const [progressEvent, setProgressEvent] = useState<JobProgressEvent | null>(null);

  const jobName = `jellyfin-library-sync-${server.id}`;
  useJobProgress(jobName, setProgressEvent);
  // Ambient truth (another tab, another user, or the schedule could have started this), not just
  // whether this component's own click is still in flight.
  const running =
    clicking || progressEvent?.kind === "started" || progressEvent?.kind === "progress";

  // Empty selection means "every library" — same convention the backend uses.
  const allSelected = selected.size === 0;

  async function toggle(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
    const ids = Array.from(next);
    await api.updateJellyfinLibrarySelection(server.id, ids);
    onServerUpdate({ selectedLibraryIds: ids });
  }

  async function selectAll() {
    setSelected(new Set());
    await api.updateJellyfinLibrarySelection(server.id, []);
    onServerUpdate({ selectedLibraryIds: [] });
  }

  async function runSync() {
    if (running) return; // already running — from this tab, another tab, or the schedule
    setClicking(true);
    try {
      const run = await api.syncJellyfinLibraries(server.id);
      showToast(
        run.status === "FAILED"
          ? `Library sync failed${run.message ? `: ${run.message}` : ""}`
          : (run.message ?? "Library sync complete."),
      );
    } catch (e) {
      // 409 means it was already running by the time the request landed — not a real failure,
      // and the progress bar (driven by useJobProgress) already reflects it.
      if (!(e instanceof ApiError && e.status === 409)) {
        showToast(e instanceof ApiError ? `Library sync failed: ${e.message}` : "Library sync failed");
      }
    } finally {
      setClicking(false);
    }
  }

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <FilmSlate size={15} className="text-faint" />
          <span className="section-label" style={{ margin: 0 }}>
            Libraries
          </span>
        </div>
        <button type="button" className="btn btn-secondary" onClick={runSync} disabled={running}>
          <ArrowsClockwise size={14} className={running ? "spin" : ""} />
          {running ? "Syncing…" : "Sync Libraries"}
        </button>
      </div>

      {loading && (
        <p className="text-faint" style={{ fontSize: 12 }}>
          Loading libraries…
        </p>
      )}
      {error && (
        <p className="text-muted" style={{ fontSize: 12 }}>
          Could not load libraries: {error}
        </p>
      )}
      {libraries?.length === 0 && (
        <p className="text-faint" style={{ fontSize: 12 }}>
          No libraries found on this server.
        </p>
      )}

      {libraries && libraries.length > 0 && (
        <>
          <div className="setup-test-row" style={{ marginTop: 0, marginBottom: 8 }}>
            <span className="setup-test-btn" onClick={selectAll}>
              {allSelected ? "All libraries selected" : "Select all"}
            </span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {libraries.map((library) => {
              const checked = allSelected || selected.has(library.id);
              return (
                <div
                  key={library.id}
                  onClick={() => toggle(library.id)}
                  className="setup-toggle-row"
                  style={{ cursor: "pointer" }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 500, fontSize: 13 }}>{library.name}</div>
                    {library.collectionType && (
                      <div className="text-faint" style={{ fontSize: 11, marginTop: 2 }}>
                        {library.collectionType}
                      </div>
                    )}
                  </div>
                  <span
                    style={{
                      display: "grid",
                      placeItems: "center",
                      width: 20,
                      height: 20,
                      borderRadius: 6,
                      border: checked ? "none" : "1px solid var(--border)",
                      background: checked ? "var(--accent-gradient)" : "transparent",
                      color: "#0b0c12",
                      flex: "none",
                    }}
                  >
                    {checked && <Check size={13} weight="bold" />}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}

      {running && <JobProgressBar event={progressEvent} />}
    </div>
  );
}

function UserSelectionPanel({
  server,
  onServerUpdate,
  showToast,
}: {
  server: JellyfinServer;
  onServerUpdate: (patch: Partial<JellyfinServer>) => void;
  showToast: (message: string) => void;
}) {
  const { data: users, loading, error } = useApi(() => api.listJellyfinUsers(server.id), [server.id]);
  const [selected, setSelected] = useState<Set<string>>(new Set(server.selectedUserIds));
  const [clicking, setClicking] = useState(false);
  const [progressEvent, setProgressEvent] = useState<JobProgressEvent | null>(null);

  const jobName = `jellyfin-user-import-${server.id}`;
  useJobProgress(jobName, setProgressEvent);
  const running =
    clicking || progressEvent?.kind === "started" || progressEvent?.kind === "progress";

  // Empty selection means "every account" — same convention the backend uses.
  const allSelected = selected.size === 0;

  async function toggle(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    setSelected(next);
    const ids = Array.from(next);
    await api.updateJellyfinUserSelection(server.id, ids);
    onServerUpdate({ selectedUserIds: ids });
  }

  async function selectAll() {
    setSelected(new Set());
    await api.updateJellyfinUserSelection(server.id, []);
    onServerUpdate({ selectedUserIds: [] });
  }

  async function runImport() {
    if (running) return; // already running — from this tab, another tab, or the schedule
    setClicking(true);
    try {
      const run = await api.syncJellyfinUsers(server.id);
      showToast(
        run.status === "FAILED"
          ? `User import failed${run.message ? `: ${run.message}` : ""}`
          : (run.message ?? "User import complete."),
      );
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 409)) {
        showToast(e instanceof ApiError ? `User import failed: ${e.message}` : "User import failed");
      }
    } finally {
      setClicking(false);
    }
  }

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginBottom: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
          <Users size={15} className="text-faint" />
          <span className="section-label" style={{ margin: 0 }}>
            Users
          </span>
        </div>
        <button type="button" className="btn btn-secondary" onClick={runImport} disabled={running}>
          <ArrowsClockwise size={14} className={running ? "spin" : ""} />
          {running ? "Importing…" : "Import Users"}
        </button>
      </div>

      {loading && (
        <p className="text-faint" style={{ fontSize: 12 }}>
          Loading users…
        </p>
      )}
      {error && (
        <p className="text-muted" style={{ fontSize: 12 }}>
          Could not load users: {error}
        </p>
      )}
      {users?.length === 0 && (
        <p className="text-faint" style={{ fontSize: 12 }}>
          No user accounts found on this server.
        </p>
      )}

      {users && users.length > 0 && (
        <>
          <div className="setup-test-row" style={{ marginTop: 0, marginBottom: 8 }}>
            <span className="setup-test-btn" onClick={selectAll}>
              {allSelected ? "All users selected" : "Select all"}
            </span>
          </div>
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            {users.map((user) => {
              const checked = allSelected || selected.has(user.id);
              return (
                <div
                  key={user.id}
                  onClick={() => toggle(user.id)}
                  className="setup-toggle-row"
                  style={{ cursor: "pointer" }}
                >
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontWeight: 500, fontSize: 13 }}>{user.name}</div>
                    {user.isAdmin && (
                      <div className="text-faint" style={{ fontSize: 11, marginTop: 2 }}>
                        Administrator
                      </div>
                    )}
                  </div>
                  <span
                    style={{
                      display: "grid",
                      placeItems: "center",
                      width: 20,
                      height: 20,
                      borderRadius: 6,
                      border: checked ? "none" : "1px solid var(--border)",
                      background: checked ? "var(--accent-gradient)" : "transparent",
                      color: "#0b0c12",
                      flex: "none",
                    }}
                  >
                    {checked && <Check size={13} weight="bold" />}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}

      {running && <JobProgressBar event={progressEvent} />}
    </div>
  );
}

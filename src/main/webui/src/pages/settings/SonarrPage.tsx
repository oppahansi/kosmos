import {
  ArrowsClockwiseIcon as ArrowsClockwise,
  CaretDownIcon as CaretDown,
  CheckCircleIcon as CheckCircle,
  FolderIcon as Folder,
  KeyIcon as Key,
  PlugsIcon as Plugs,
  PlusIcon as Plus,
  WarningCircleIcon as WarningCircle,
  XIcon as X,
} from "@phosphor-icons/react";
import { useState } from "react";
import { api, ApiError } from "../../api/client";
import type { SonarrServer, JobProgressEvent } from "../../api/types";
import { JobProgressBar } from "../../components/JobProgressBar";
import { useApi } from "../../hooks/useApi";
import { useJobProgress } from "../../hooks/useJobProgress";

export default function SonarrPage() {
  const { data: servers, error: loadError, reload } = useApi(() => api.listSonarrServers(), []);
  const [modalOpen, setModalOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast((current) => (current === message ? null : current)), 6000);
  }

  return (
    <div>
      <div className="page-header" style={{ padding: "0 0 4px" }}>
        <div style={{ flex: 1, minWidth: 280 }}>
          <h2 style={{ marginBottom: 6 }}>Sonarr</h2>
          <p className="text-muted" style={{ maxWidth: "60ch" }}>
            Migrating from Sonarr? Connect it here to import your existing series library — every
            episode Sonarr has already downloaded lands in Kosmos, matched by TMDB id. Series land
            as regular Shows; anime isn't auto-classified from Sonarr — sync via Jellyfin instead if
            you want that.
          </p>
        </div>
        <button type="button" className="btn btn-hero" onClick={() => setModalOpen(true)}>
          <Plus size={15} weight="bold" />
          Add Server
        </button>
      </div>

      {loadError && <p className="text-muted">Failed to load Sonarr servers: {loadError}</p>}
      {servers?.length === 0 && <p className="text-muted">No Sonarr server connected yet.</p>}

      {servers?.map((server) => (
        <SonarrServerRow key={server.id} server={server} showToast={showToast} />
      ))}

      {modalOpen && (
        <AddSonarrServerModal
          onClose={() => setModalOpen(false)}
          onCreated={() => {
            setModalOpen(false);
            reload();
            showToast("Sonarr server added — sync its library below.");
          }}
        />
      )}

      {toast && (
        <div className="toast">
          <span
            className="toast-icon"
            style={{ background: "rgba(79,191,139,.16)", color: "var(--status-good)" }}
          >
            <CheckCircle size={13} weight="fill" />
          </span>
          {toast}
        </div>
      )}
    </div>
  );
}

function SonarrServerRow({
  server,
  showToast,
}: {
  server: SonarrServer;
  showToast: (message: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [clicking, setClicking] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [progressEvent, setProgressEvent] = useState<JobProgressEvent | null>(null);

  const jobName = `sonarr-library-sync-${server.id}`;
  useJobProgress(jobName, setProgressEvent);
  const running =
    clicking || progressEvent?.kind === "started" || progressEvent?.kind === "progress";

  async function runSync() {
    if (running) return;
    setClicking(true);
    try {
      const run = await api.syncSonarrLibrary(server.id);
      showToast(
        run.status === "FAILED"
          ? `Library sync failed${run.message ? `: ${run.message}` : ""}`
          : (run.message ?? "Library sync complete."),
      );
    } catch (e) {
      if (!(e instanceof ApiError && e.status === 409)) {
        showToast(e instanceof ApiError ? `Library sync failed: ${e.message}` : "Library sync failed");
      }
    } finally {
      setClicking(false);
    }
  }

  async function registerRootFolders() {
    if (registering) return;
    setRegistering(true);
    try {
      const result = await api.autoRegisterRootFoldersFromSonarr(server.id);
      showToast(`Registered ${result.registered} folder${result.registered === 1 ? "" : "s"}.`);
    } catch (e) {
      showToast(e instanceof ApiError ? `Could not register folders: ${e.message}` : "Could not register folders");
    } finally {
      setRegistering(false);
    }
  }

  return (
    <div
      className={`indexer-row${server.enabled ? "" : " disabled"}`}
      style={{ flexDirection: "column", alignItems: "stretch" }}
    >
      <div
        style={{ display: "flex", alignItems: "center", gap: 14, cursor: "pointer" }}
        onClick={() => setExpanded((v) => !v)}
      >
        <span className="icon-tile">
          <ArrowsClockwise size={16} />
        </span>
        <div className="indexer-row-main">
          <div className="indexer-row-title-line">
            <span className="indexer-row-name">{server.name}</span>
          </div>
          <div className="indexer-row-sub">
            <span>{server.baseUrl}</span>
            <span className="text-faint">·</span>
            <span>{server.apiKeySet ? "API key set" : "no API key"}</span>
          </div>
        </div>
        <CaretDown size={14} className={`job-row-chevron${expanded ? " open" : ""}`} />
      </div>

      {expanded && (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 12,
            marginTop: 16,
            paddingTop: 16,
            borderTop: "1px solid var(--border-subtle)",
          }}
        >
          <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
            <button type="button" className="btn btn-secondary" onClick={runSync} disabled={running}>
              <ArrowsClockwise size={14} className={running ? "spin" : ""} />
              {running ? "Syncing…" : "Sync Library"}
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={registerRootFolders}
              disabled={registering}
            >
              <Folder size={14} />
              {registering ? "Registering…" : "Register Root Folders"}
            </button>
          </div>
          {running && <JobProgressBar event={progressEvent} />}
        </div>
      )}
    </div>
  );
}

function AddSonarrServerModal({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [testedKey, setTestedKey] = useState<string | null>(null);

  const connectionKey = `${baseUrl.trim()}|${apiKey.trim()}`;
  const verified = testResult?.ok === true && testedKey === connectionKey;
  const showTestResult = testResult !== null && testedKey === connectionKey;

  const canTest = baseUrl.trim().length > 0 && apiKey.trim().length > 0 && !testing;
  const valid = name.trim().length > 0 && verified;

  async function testConnection() {
    if (!canTest) return;
    setTesting(true);
    try {
      const result = await api.testSonarrConnection({ baseUrl: baseUrl.trim(), apiKey: apiKey.trim() });
      setTestedKey(connectionKey);
      setTestResult({ ok: result.ok, message: result.message });
    } catch (e) {
      setTestedKey(connectionKey);
      setTestResult({ ok: false, message: e instanceof Error ? e.message : "Connection failed" });
    } finally {
      setTesting(false);
    }
  }

  async function save() {
    if (!valid || saving) return;
    setSaving(true);
    setError(null);
    try {
      await api.createSonarrServer({ name, baseUrl, apiKey });
      onCreated();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-header">
          <span className="icon-tile">
            <ArrowsClockwise size={16} />
          </span>
          <div className="dialog-header-body">
            <div className="dialog-title">Add Sonarr server</div>
            <div className="dialog-sub">Create an API key under Settings → General in Sonarr.</div>
          </div>
          <button type="button" className="dialog-close" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="field">
          <label>Name</label>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Sonarr" />
        </div>

        <div className="field">
          <label>Base URL</label>
          <input
            className="input"
            style={{ fontFamily: "var(--font-mono)" }}
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder="http://192.168.1.10:8989"
          />
        </div>

        <div className="field">
          <label>API key</label>
          <div style={{ position: "relative" }}>
            <Key size={14} style={{ position: "absolute", left: 12, top: 13, color: "var(--text-faint)" }} />
            <input
              className="input"
              style={{ paddingLeft: 34 }}
              type={showKey ? "text" : "password"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            <button
              type="button"
              onClick={() => setShowKey((v) => !v)}
              style={{
                position: "absolute",
                right: 10,
                top: 10,
                background: "transparent",
                border: 0,
                color: "var(--text-faint)",
                cursor: "pointer",
              }}
            >
              {showKey ? "hide" : "show"}
            </button>
          </div>
        </div>

        <div className="field">
          <button type="button" className="btn btn-secondary" onClick={testConnection} disabled={!canTest}>
            <Plugs size={14} />
            {testing ? "Testing…" : "Test Connection"}
          </button>
          {showTestResult && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 7,
                marginTop: 8,
                fontSize: 12.5,
                color: testResult?.ok ? "var(--status-good-text)" : "var(--status-bad-text)",
              }}
            >
              {testResult?.ok ? <CheckCircle size={14} weight="fill" /> : <WarningCircle size={14} weight="fill" />}
              {testResult?.message}
            </div>
          )}
          {!showTestResult && (
            <p className="text-faint" style={{ fontSize: 11.5, marginTop: 8 }}>
              A successful test is required before this server can be saved.
            </p>
          )}
        </div>

        {error && <p className="text-muted">{error}</p>}

        <div className="dialog-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn-hero" onClick={save} disabled={!valid || saving}>
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}

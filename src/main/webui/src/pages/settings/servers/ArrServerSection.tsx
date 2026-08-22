import { ArrowsClockwiseIcon as ArrowsClockwise, FolderIcon as Folder, PlusIcon as Plus } from "@phosphor-icons/react";
import { useState } from "react";
import { ApiError } from "../../../api/client";
import type { JobProgressEvent, JobRun } from "../../../api/types";
import { AddServerModal } from "../../../components/settings/AddServerModal";
import { JobProgressBar } from "../../../components/JobProgressBar";
import { ServerRowHeader } from "../../../components/settings/ServerRowHeader";
import { useApi } from "../../../hooks/useApi";
import { useJobProgress } from "../../../hooks/useJobProgress";

/** A registered server's minimal shared shape — {@code RadarrServer}/{@code SonarrServer} both satisfy it. */
interface ArrServer {
  id: string;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  enabled: boolean;
}

/**
 * Radarr and Sonarr's own settings sections are otherwise identical — same fields, same
 * test/save/sync/register-root-folders flow — so this one component covers both, parameterized by
 * which app it's talking to. See {@link import("../ServersPage").default} for where it's used twice.
 */
export interface ArrKindConfig {
  label: string;
  icon: React.ReactNode;
  description: React.ReactNode;
  namePlaceholder: string;
  urlPlaceholder: string;
  jobNamePrefix: string;
  listServers: () => Promise<ArrServer[]>;
  testConnection: (input: { baseUrl: string; apiKey: string }) => Promise<{ ok: boolean; message: string }>;
  createServer: (input: { name: string; baseUrl: string; apiKey: string }) => Promise<unknown>;
  syncLibrary: (id: string) => Promise<JobRun>;
  registerRootFolders: (id: string) => Promise<{ registered: number; skipped: number }>;
}

export function ArrServerSection({ config, showToast }: { config: ArrKindConfig; showToast: (message: string) => void }) {
  // config never changes across this section's lifetime (its identity comes from a stable
  // module-level constant in ServersPage), so an empty dep array is correct — not "run once by
  // accident."
  const { data: servers, error: loadError, reload } = useApi(config.listServers, []);
  const [modalOpen, setModalOpen] = useState(false);

  return (
    <div>
      <div className="page-header" style={{ padding: "0 0 4px" }}>
        <div style={{ flex: 1, minWidth: 280 }}>
          <h3 style={{ marginBottom: 6 }}>{config.label}</h3>
          <p className="text-muted" style={{ maxWidth: "60ch" }}>
            {config.description}
          </p>
        </div>
        <button type="button" className="btn btn-hero" onClick={() => setModalOpen(true)}>
          <Plus size={15} weight="bold" />
          Add Server
        </button>
      </div>

      {loadError && (
        <p className="text-muted">
          Failed to load {config.label} servers: {loadError}
        </p>
      )}
      {servers?.length === 0 && <p className="text-muted">No {config.label} server connected yet.</p>}

      {servers?.map((server) => (
        <ArrServerRow key={server.id} server={server} config={config} showToast={showToast} />
      ))}

      {modalOpen && (
        <AddServerModal
          icon={config.icon}
          title={`Add ${config.label} server`}
          helpText={`Create an API key under Settings → General in ${config.label}.`}
          namePlaceholder={config.namePlaceholder}
          urlPlaceholder={config.urlPlaceholder}
          testConnection={config.testConnection}
          createServer={config.createServer}
          onClose={() => setModalOpen(false)}
          onCreated={() => {
            setModalOpen(false);
            reload();
            showToast(`${config.label} server added — sync its library below.`);
          }}
        />
      )}
    </div>
  );
}

function ArrServerRow({
  server,
  config,
  showToast,
}: {
  server: ArrServer;
  config: ArrKindConfig;
  showToast: (message: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const [clicking, setClicking] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [progressEvent, setProgressEvent] = useState<JobProgressEvent | null>(null);

  const jobName = `${config.jobNamePrefix}-${server.id}`;
  useJobProgress(jobName, setProgressEvent);
  const running =
    clicking || progressEvent?.kind === "started" || progressEvent?.kind === "progress";

  async function runSync() {
    if (running) return;
    setClicking(true);
    try {
      const run = await config.syncLibrary(server.id);
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
      const result = await config.registerRootFolders(server.id);
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
      <ServerRowHeader
        icon={config.icon}
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

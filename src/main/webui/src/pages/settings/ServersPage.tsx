import { ArrowsClockwiseIcon as ArrowsClockwise, CheckCircleIcon as CheckCircle } from "@phosphor-icons/react";
import { useState } from "react";
import { api } from "../../api/client";
import { type ArrKindConfig, ArrServerSection } from "./servers/ArrServerSection";
import { JellyfinSection } from "./servers/JellyfinSection";

const RADARR_CONFIG: ArrKindConfig = {
  label: "Radarr",
  icon: <ArrowsClockwise size={16} />,
  description: (
    <>
      Migrating from Radarr? Connect it here to import your existing movie library — every movie
      with a file already on disk lands in Kosmos, matched by TMDB id. Movies Radarr has monitored
      but not yet downloaded aren't imported.
    </>
  ),
  namePlaceholder: "Radarr",
  urlPlaceholder: "http://192.168.1.10:7878",
  jobNamePrefix: "radarr-library-sync",
  listServers: () => api.listRadarrServers(),
  testConnection: api.testRadarrConnection,
  createServer: api.createRadarrServer,
  syncLibrary: (id) => api.syncRadarrLibrary(id),
  registerRootFolders: (id) => api.autoRegisterRootFoldersFromRadarr(id),
};

const SONARR_CONFIG: ArrKindConfig = {
  label: "Sonarr",
  icon: <ArrowsClockwise size={16} />,
  description: (
    <>
      Migrating from Sonarr? Connect it here to import your existing series library — every episode
      Sonarr has already downloaded lands in Kosmos, matched by TMDB id. Series land as regular
      Shows; anime isn't auto-classified from Sonarr — sync via Jellyfin instead if you want that.
    </>
  ),
  namePlaceholder: "Sonarr",
  urlPlaceholder: "http://192.168.1.10:8989",
  jobNamePrefix: "sonarr-library-sync",
  listServers: () => api.listSonarrServers(),
  testConnection: api.testSonarrConnection,
  createServer: api.createSonarrServer,
  syncLibrary: (id) => api.syncSonarrLibrary(id),
  registerRootFolders: (id) => api.autoRegisterRootFoldersFromSonarr(id),
};

const SECTION_DIVIDER: React.CSSProperties = {
  marginTop: 36,
  paddingTop: 28,
  borderTop: "1px solid var(--border-subtle)",
};

/**
 * Every external media server Kosmos talks to — Jellyfin (already-scanned library + user import)
 * and Radarr/Sonarr (migrate an existing *arr library) — consolidated onto one settings tab instead
 * of three, since they're all the same underlying concern ("bring in what another server already
 * knows about") a user reaches for together. One shared toast covers all three sections so a sync
 * kicked off in one doesn't get its confirmation lost under another's.
 */
export default function ServersPage() {
  const [toast, setToast] = useState<string | null>(null);

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast((current) => (current === message ? null : current)), 6000);
  }

  return (
    <div>
      <JellyfinSection showToast={showToast} />

      <div style={SECTION_DIVIDER}>
        <ArrServerSection config={RADARR_CONFIG} showToast={showToast} />
      </div>

      <div style={SECTION_DIVIDER}>
        <ArrServerSection config={SONARR_CONFIG} showToast={showToast} />
      </div>

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

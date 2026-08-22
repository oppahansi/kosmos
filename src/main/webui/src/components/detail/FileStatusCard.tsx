import { InfoIcon as Info } from "@phosphor-icons/react";
import { useState } from "react";
import type { LibraryFile, QualityProfile } from "../../api/types";
import { formatBytes } from "../../utils/formatBytes";

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.round((seconds % 3600) / 60);
  return `${h}h ${m}m`;
}

function formatResolution(width: number | null, height: number | null): string {
  if (!height) return width && height ? `${width}×${height}` : "Unknown resolution";
  if (height >= 2000) return "2160p";
  if (height >= 1000) return "1080p";
  if (height >= 700) return "720p";
  return `${height}p`;
}

function relativeDays(iso: string): string {
  const days = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000));
  if (days === 0) return "today";
  if (days === 1) return "1 day ago";
  return `${days} days ago`;
}

/** Movie-only "In library — file on disk" / "Missing — no file on disk" card below the action row. */
export function FileStatusCard({
  file,
  addedAt,
  activeProfile,
}: {
  file: LibraryFile | null;
  addedAt: string;
  activeProfile: QualityProfile | null;
}) {
  const [pathCopied, setPathCopied] = useState(false);

  async function copyPath(path: string) {
    await navigator.clipboard.writeText(path);
    setPathCopied(true);
    setTimeout(() => setPathCopied(false), 1500);
  }

  if (file) {
    return (
      <div className="info-card">
        <div className="info-card-header">
          <span className="dot dot-good" />
          <span style={{ fontWeight: 500, fontSize: 13.5, color: "var(--status-good-text)" }}>In library — file on disk</span>
          <div style={{ flex: 1 }} />
          <span className="text-faint" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
            imported {relativeDays(file.importedAt)}
          </span>
        </div>
        <div className="info-card-facts">
          <div className="info-card-fact">
            <div className="k">Quality</div>
            <div className="v">
              {formatResolution(file.resolutionWidth, file.resolutionHeight)}
              {file.videoCodec ? ` ${file.videoCodec}` : ""}
              {file.hdrFormat ? ` · ${file.hdrFormat}` : ""}
            </div>
          </div>
          <div className="info-card-fact">
            <div className="k">Size</div>
            <div className="v">{formatBytes(file.sizeBytes)}</div>
          </div>
          <div className="info-card-fact">
            <div className="k">Duration</div>
            <div className="v">{file.durationSeconds ? formatDuration(file.durationSeconds) : "Not probed"}</div>
          </div>
          <div className="info-card-fact">
            <div className="k">Container</div>
            <div className="v">{file.container ?? "Not probed"}</div>
          </div>
        </div>
        <div className="info-card-footer">
          <Info size={14} className="text-faint" />
          <span className="info-card-path">{file.path}</span>
          <span className="info-card-copy" onClick={() => copyPath(file.path)}>
            {pathCopied ? "Copied" : "Copy"}
          </span>
        </div>
      </div>
    );
  }

  return (
    <div className="info-card bad">
      <div className="info-card-header">
        <span className="dot dot-bad" />
        <span style={{ fontWeight: 500, fontSize: 13.5, color: "var(--status-bad-text)" }}>Missing — no file on disk</span>
        <div style={{ flex: 1 }} />
        <span className="text-faint" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
          added {relativeDays(addedAt)}
        </span>
      </div>
      <div className="info-card-facts">
        <div className="info-card-fact">
          <div className="k">Quality profile</div>
          <div className="v">{activeProfile ? activeProfile.name : "None"}</div>
        </div>
      </div>
      <div className="info-card-footer">
        <Info size={14} className="text-faint" />
        <span style={{ flex: 1 }}>
          {activeProfile
            ? `Kosmos checks every enabled indexer against "${activeProfile.name}" every 6 hours until this scores above cutoff.`
            : "Assign a quality profile above to let Kosmos search for this automatically, or search yourself now."}
        </span>
      </div>
    </div>
  );
}

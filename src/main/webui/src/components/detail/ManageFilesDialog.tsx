import { PushPinIcon as PushPin, TrashIcon as Trash, XIcon as X } from "@phosphor-icons/react";
import { useState } from "react";
import { api } from "../../api/client";
import type { LibraryFile } from "../../api/types";
import { formatBytes } from "../../utils/formatBytes";
import { ConfirmDialog } from "../ConfirmDialog";
import { Toggle } from "../Toggle";

/**
 * Most movies have exactly one file, but the model (and Radarr's own UI) supports several — a
 * quality upgrade that failed to clean up the old file, multiple editions/cuts. This is additive
 * to FileStatusCard (which keeps showing the primary file), not a replacement for it.
 */
export function ManageFilesDialog({
  files,
  onClose,
  onChanged,
}: {
  files: LibraryFile[];
  onClose: () => void;
  onChanged: () => void;
}) {
  const [deleteTarget, setDeleteTarget] = useState<LibraryFile | null>(null);
  const [deleteFromDisk, setDeleteFromDisk] = useState(false);

  async function confirmDelete() {
    if (!deleteTarget) return;
    await api.deleteLibraryFile(deleteTarget.id, deleteFromDisk);
    setDeleteTarget(null);
    onChanged();
  }

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div className="dialog" style={{ maxWidth: 640 }} onClick={(e) => e.stopPropagation()}>
        <div className="dialog-header">
          <div className="dialog-header-body">
            <div className="dialog-title">Manage Files</div>
            <div className="dialog-sub">
              {files.length} file{files.length === 1 ? "" : "s"} matched to this title
            </div>
          </div>
          <button type="button" className="dialog-close" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 4 }}>
          {files.map((file) => (
            <div
              key={file.id}
              style={{
                border: "1px solid var(--border-subtle)",
                borderRadius: 10,
                padding: 12,
                display: "flex",
                flexDirection: "column",
                gap: 6,
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span
                  className="text-faint"
                  style={{ fontFamily: "var(--font-mono)", fontSize: 11.5, flex: 1, wordBreak: "break-all" }}
                >
                  {file.path}
                </span>
                {file.matchPinned && (
                  <span title="Match pinned">
                    <PushPin size={13} className="text-faint" />
                  </span>
                )}
                <button
                  type="button"
                  className="btn-icon"
                  style={{ width: 26, height: 26, flex: "none" }}
                  aria-label="Delete file"
                  onClick={() => {
                    setDeleteFromDisk(false);
                    setDeleteTarget(file);
                  }}
                >
                  <Trash size={13} />
                </button>
              </div>
              <div className="text-faint" style={{ fontSize: 11 }}>
                {formatBytes(file.sizeBytes)} · {file.matchMethod} · {(file.matchConfidence * 100).toFixed(0)}% confidence
                {file.container ? ` · ${file.container}` : ""}
                {file.videoCodec ? ` · ${file.videoCodec}` : ""}
              </div>
            </div>
          ))}
          {files.length === 0 && <p className="text-muted">No files matched to this title.</p>}
        </div>
      </div>

      {deleteTarget && (
        <ConfirmDialog
          title="Delete this file?"
          body="This removes Kosmos's record of it. It won't come back on the next scan unless the file is re-matched."
          confirmLabel="Delete"
          extra={
            <label style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 13, marginTop: 4 }}>
              <Toggle on={deleteFromDisk} onChange={setDeleteFromDisk} />
              Also delete the file from disk
            </label>
          }
          onConfirm={confirmDelete}
          onCancel={() => setDeleteTarget(null)}
        />
      )}
    </div>
  );
}

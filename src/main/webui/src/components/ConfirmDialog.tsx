import { WarningIcon as Warning, XIcon as X } from "@phosphor-icons/react";
import { useState } from "react";

/**
 * Generic destructive-action confirmation — replaces the app's only prior confirm pattern (a bare
 * `window.confirm` in BackupPage) with a real dialog matching the `.dialog`/`.dialog-backdrop`
 * modal convention every other modal in the app already uses (see e.g. AddServerModal). Shared by
 * any delete action rather than each screen inventing its own.
 */
export function ConfirmDialog({
  title,
  body,
  confirmLabel = "Delete",
  extra,
  onConfirm,
  onCancel,
}: {
  title: string;
  body: string;
  confirmLabel?: string;
  /** Optional extra control rendered between the body text and the footer, e.g. a checkbox. */
  extra?: React.ReactNode;
  onConfirm: () => Promise<void>;
  onCancel: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    if (confirming) return;
    setConfirming(true);
    setError(null);
    try {
      await onConfirm();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setConfirming(false);
    }
  }

  return (
    <div className="dialog-backdrop" onClick={onCancel}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-header">
          <span className="icon-tile" style={{ color: "var(--status-bad-text)" }}>
            <Warning size={16} />
          </span>
          <div className="dialog-header-body">
            <div className="dialog-title">{title}</div>
          </div>
          <button type="button" className="dialog-close" onClick={onCancel}>
            <X size={15} />
          </button>
        </div>

        <p className="text-muted" style={{ marginTop: 4 }}>
          {body}
        </p>

        {extra}

        {error && <p className="text-muted">{error}</p>}

        <div className="dialog-footer">
          <button type="button" className="btn btn-secondary" onClick={onCancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn"
            style={{ background: "var(--status-bad)", color: "#fff" }}
            onClick={confirm}
            disabled={confirming}
          >
            {confirming ? "Working…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

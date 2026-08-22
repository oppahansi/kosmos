import { CaretDownIcon as CaretDown, CheckIcon as Check, FolderIcon as Folder } from "@phosphor-icons/react";
import { useState } from "react";
import type { LibraryRootFolder } from "../../api/types";

/** Root-folder reassignment — Radarr/Sonarr's "Edit" root-folder field, same dropdown convention as {@link import("./QualityProfileDropdown").QualityProfileDropdown}. */
export function RootFolderDropdown({
  folders,
  activeFolderId,
  onSelect,
}: {
  folders: LibraryRootFolder[];
  activeFolderId: string | null;
  onSelect: (rootFolderId: string) => Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const active = folders.find((f) => f.id === activeFolderId);

  async function select(rootFolderId: string) {
    setOpen(false);
    setSaving(true);
    try {
      await onSelect(rootFolderId);
    } finally {
      setSaving(false);
    }
  }

  if (folders.length === 0) return null;

  return (
    <div className="dropdown-wrap">
      <button
        type="button"
        className={`btn btn-secondary${open ? " open" : ""}`}
        disabled={saving}
        onClick={() => setOpen((o) => !o)}
        title={active?.path}
      >
        <Folder size={15} />
        {saving ? "Saving…" : active ? active.path : "Root folder"}
        <CaretDown size={11} className="text-faint" />
      </button>
      {open && (
        <div className="grab-client-menu">
          {folders.map((f) => (
            <div key={f.id} className={`grab-client-item${f.id === activeFolderId ? " active" : ""}`} onClick={() => select(f.id)}>
              <Folder size={14} className="text-muted" />
              <span style={{ flex: 1, minWidth: 0, fontSize: 12.5, wordBreak: "break-all" }}>{f.path}</span>
              {f.id === activeFolderId && <Check size={12} color="var(--accent-tint)" />}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

import { CalendarCheckIcon as CalendarCheck, CaretDownIcon as CaretDown, EyeIcon as Eye, EyeSlashIcon as EyeSlash, MagnifyingGlassIcon as MagnifyingGlass } from "@phosphor-icons/react";
import { useState } from "react";
import type { SeriesMonitoringMode } from "../../api/types";

const OPTIONS: { mode: SeriesMonitoringMode; label: string; icon: typeof Eye }[] = [
  { mode: "ALL", label: "Monitor all episodes", icon: Eye },
  { mode: "FUTURE", label: "Monitor future episodes only", icon: CalendarCheck },
  { mode: "MISSING", label: "Monitor missing episodes only", icon: MagnifyingGlass },
  { mode: "NONE", label: "Monitor no episodes", icon: EyeSlash },
];

/** Sonarr's "Series Monitoring" bulk presets — applies to every season at once. */
export function SeriesMonitoringDropdown({ onSelect }: { onSelect: (mode: SeriesMonitoringMode) => Promise<void> }) {
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);

  async function select(mode: SeriesMonitoringMode) {
    setOpen(false);
    setSaving(true);
    try {
      await onSelect(mode);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="dropdown-wrap">
      <button type="button" className={`btn btn-secondary${open ? " open" : ""}`} disabled={saving} onClick={() => setOpen((o) => !o)}>
        <Eye size={15} />
        {saving ? "Saving…" : "Series Monitoring"}
        <CaretDown size={11} className="text-faint" />
      </button>
      {open && (
        <div className="grab-client-menu" style={{ minWidth: 220 }}>
          {OPTIONS.map(({ mode, label, icon: Icon }) => (
            <div key={mode} className="grab-client-item" onClick={() => select(mode)}>
              <Icon size={14} className="text-muted" />
              <span style={{ flex: 1, minWidth: 0, fontSize: 12.5 }}>{label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

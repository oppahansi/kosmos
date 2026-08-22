import { DotsThreeIcon as DotsThree } from "@phosphor-icons/react";
import { useState } from "react";

export interface MoreAction {
  label: string;
  icon: React.ReactNode;
  onClick: () => void;
  destructive?: boolean;
}

/**
 * The "..." trigger on a movie/show/anime detail page — same `dropdown-wrap`/`grab-client-menu`
 * popover convention {@link import("./QualityProfileDropdown").QualityProfileDropdown} already
 * uses, reused here rather than inventing a second dropdown pattern. Home for per-title actions as
 * they land (Delete today; Refresh & Scan, Manage Files, Preview Rename in later phases).
 */
export function MoreActionsMenu({ actions }: { actions: MoreAction[] }) {
  const [open, setOpen] = useState(false);

  return (
    <div className="dropdown-wrap">
      <button
        type="button"
        className={`btn btn-icon${open ? " open" : ""}`}
        aria-label="More actions"
        onClick={() => setOpen((o) => !o)}
      >
        <DotsThree size={17} />
      </button>
      {open && (
        <div className="grab-client-menu" style={{ left: "auto", right: 0, minWidth: 180 }}>
          {actions.map((action) => (
            <div
              key={action.label}
              className="grab-client-item"
              style={{
                whiteSpace: "nowrap",
                ...(action.destructive ? { color: "var(--status-bad-text)" } : undefined),
              }}
              onClick={() => {
                setOpen(false);
                action.onClick();
              }}
            >
              {action.icon}
              <span style={{ flex: 1, minWidth: 0, fontSize: 12.5 }}>{action.label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

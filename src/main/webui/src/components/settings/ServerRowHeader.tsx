import { CaretDownIcon as CaretDown } from "@phosphor-icons/react";

/** The collapsible name/URL/API-key-set header every server row on the Servers page shares. */
export function ServerRowHeader({
  icon,
  name,
  baseUrl,
  apiKeySet,
  expanded,
  onToggle,
}: {
  icon: React.ReactNode;
  name: string;
  baseUrl: string;
  apiKeySet: boolean;
  expanded: boolean;
  onToggle: () => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 14, cursor: "pointer" }} onClick={onToggle}>
      <span className="icon-tile">{icon}</span>
      <div className="indexer-row-main">
        <div className="indexer-row-title-line">
          <span className="indexer-row-name">{name}</span>
        </div>
        <div className="indexer-row-sub">
          <span>{baseUrl}</span>
          <span className="text-faint">·</span>
          <span>{apiKeySet ? "API key set" : "no API key"}</span>
        </div>
      </div>
      <CaretDown size={14} className={`job-row-chevron${expanded ? " open" : ""}`} />
    </div>
  );
}

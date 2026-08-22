import { NavLink, Outlet } from "react-router-dom";

const TABS = [
  { to: "/settings/root-folders", label: "Media Folders" },
  { to: "/settings/indexers", label: "Indexers" },
  { to: "/settings/download-clients", label: "Download Clients" },
  { to: "/settings/import-lists", label: "Import Lists" },
  { to: "/settings/plugins", label: "Plugins" },
  { to: "/settings/quality", label: "Quality Profiles" },
  { to: "/settings/size-limits", label: "Size Limits" },
  { to: "/settings/naming", label: "Naming" },
  { to: "/settings/notifications", label: "Notifications" },
  { to: "/settings/jobs", label: "Jobs" },
  { to: "/settings/health", label: "Health" },
  { to: "/settings/backup", label: "Backup" },
  { to: "/settings/servers", label: "Servers" },
  { to: "/settings/users", label: "Users" },
  { to: "/settings/permissions", label: "Permissions" },
];

export default function SettingsLayout() {
  return (
    <div className="page with-top-padding">
      <div className="page-header">
        <h1>Settings</h1>
      </div>

      <nav className="settings-tabs">
        {TABS.map(({ to, label }) => (
          <NavLink key={to} to={to} className={({ isActive }) => (isActive ? "active" : "")}>
            {label}
          </NavLink>
        ))}
      </nav>

      <Outlet />
    </div>
  );
}

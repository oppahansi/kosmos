import {
  InfoIcon as Info,
  KeyIcon as Key,
  ShieldCheckIcon as ShieldCheck,
  UserIcon,
  UserPlusIcon as UserPlus,
  XIcon as X,
} from "@phosphor-icons/react";
import { useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../../api/client";
import { useApi } from "../../hooks/useApi";

function relativeTime(iso: string): string {
  const days = Math.floor((Date.now() - new Date(iso).getTime()) / (1000 * 60 * 60 * 24));
  if (days <= 0) return "today";
  if (days === 1) return "1 day ago";
  return `${days} days ago`;
}

export default function UsersPage() {
  const { data: users, error: loadError, reload } = useApi(() => api.listUsers(), []);
  const [inviteOpen, setInviteOpen] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast((current) => (current === message ? null : current)), 4200);
  }

  return (
    <div>
      <div className="page-header" style={{ padding: "0 0 6px" }}>
        <h1>Users & access</h1>
      </div>
      <p className="text-muted" style={{ marginBottom: 20 }}>
        Native accounts, plus anyone imported from a connected Jellyfin server.
      </p>

      <div className="info-banner">
        <Info size={16} />
        Connecting and syncing a Jellyfin server (including its user accounts) happens under{" "}
        <Link to="/settings/servers">Settings → Servers</Link> — the server's own admin account
        automatically becomes a Kosmos admin, no manual step needed.
      </div>

      {loadError && <p className="text-muted">Failed to load users: {loadError}</p>}

      <div className="users-section-head">
        <div>
          <h3 style={{ margin: 0 }}>Accounts</h3>
          {users && (
            <span className="text-faint" style={{ fontSize: 12 }}>
              {users.length} account{users.length === 1 ? "" : "s"} · {users.filter((u) => u.role === "ADMIN").length} admin
            </span>
          )}
        </div>
        <button type="button" className="btn btn-secondary" onClick={() => setInviteOpen(true)}>
          <UserPlus size={15} />
          Create user
        </button>
      </div>

      <div className="users-table-head">
        <span>Account</span>
        <span>Role</span>
        <span>Origin</span>
        <span style={{ textAlign: "right" }}>Joined</span>
      </div>

      {users?.map((user) => {
        const initials = user.displayName
          .split(" ")
          .map((p) => p[0])
          .slice(0, 2)
          .join("")
          .toUpperCase();
        return (
          <div key={user.id} className="users-table-row">
            <div className="users-table-account">
              <span className="avatar">{initials}</span>
              <div className="users-table-account-main">
                <div className="users-table-name-row">
                  <span style={{ fontWeight: 500, fontSize: 13.5 }}>{user.displayName}</span>
                </div>
                <div className="users-table-handle">@{user.username}</div>
              </div>
            </div>

            <span className={`role-badge ${user.role === "ADMIN" ? "Admin" : "User"}`}>
              {user.role === "ADMIN" ? <ShieldCheck size={12} /> : <UserIcon size={12} />}
              {user.role === "ADMIN" ? "Admin" : "User"}
            </span>

            <span className="text-faint" style={{ fontSize: 11.5 }}>
              {user.jellyfinLinked ? <span className="jf-badge">Jellyfin</span> : "Native"}
            </span>

            <span className="text-faint" style={{ fontSize: 12, textAlign: "right" }}>
              {relativeTime(user.createdAt)}
            </span>
          </div>
        );
      })}

      <div className="users-footnote">Editing and removing accounts isn't supported by the API yet.</div>

      {inviteOpen && (
        <CreateUserModal
          onClose={() => setInviteOpen(false)}
          onCreated={(name) => {
            setInviteOpen(false);
            reload();
            showToast(`${name} created`);
          }}
        />
      )}

      {toast && (
        <div className="toast">
          <span className="toast-icon" style={{ background: "rgba(79,191,139,.18)", color: "var(--status-good-text)" }}>
            <UserPlus size={12} />
          </span>
          <span style={{ fontSize: 12.5 }}>{toast}</span>
        </div>
      )}
    </div>
  );
}

function CreateUserModal({ onClose, onCreated }: { onClose: () => void; onCreated: (name: string) => void }) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [role, setRole] = useState<"USER" | "ADMIN">("USER");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = username.trim().length > 0 && displayName.trim().length > 0 && password.length >= 8;

  async function save() {
    if (!valid || saving) return;
    setSaving(true);
    setError(null);
    try {
      await api.createUser({ username, displayName, password, role });
      onCreated(displayName);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div className="dialog" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-header">
          <span className="icon-tile">
            <UserPlus size={16} />
          </span>
          <div className="dialog-header-body">
            <div className="dialog-title">Create a native user</div>
            <div className="dialog-sub">You set their password directly — share it with them yourself.</div>
          </div>
          <button type="button" className="dialog-close" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="field">
          <label>Username</label>
          <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label>Display name</label>
          <input className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} />
        </div>
        <div className="field">
          <label>Password</label>
          <div style={{ position: "relative" }}>
            <Key size={14} style={{ position: "absolute", left: 12, top: 13, color: "var(--text-faint)" }} />
            <input
              className="input"
              style={{ paddingLeft: 34 }}
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <span className="text-faint" style={{ fontSize: 11 }}>
            At least 8 characters.
          </span>
        </div>
        <div className="field">
          <label>Role</label>
          <div className="seg" style={{ width: "100%" }}>
            {(["USER", "ADMIN"] as const).map((r) => (
              <button key={r} className={role === r ? "active" : ""} style={{ flex: 1 }} onClick={() => setRole(r)}>
                {r === "USER" ? "User" : "Admin"}
              </button>
            ))}
          </div>
        </div>

        {error && <p className="text-muted">{error}</p>}

        <div className="dialog-footer">
          <span className="dialog-hint">
            <Info size={13} />
            {valid ? "Looks good." : "Username, display name, and an 8+ character password are required."}
          </span>
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn-hero" disabled={!valid || saving} onClick={save}>
            {saving ? "Creating…" : "Create user"}
          </button>
        </div>
      </div>
    </div>
  );
}

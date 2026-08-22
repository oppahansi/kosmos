import {
  ArrowLeftIcon as ArrowLeft,
  ArrowRightIcon as ArrowRight,
  BellIcon as Bell,
  MagnifyingGlassIcon as MagnifyingGlass,
  SignOutIcon as SignOut,
} from "@phosphor-icons/react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../auth/AuthContext";
import { useActiveGrabCount } from "../../hooks/useActiveGrabCount";
import { useAppHistory } from "../../hooks/useAppHistory";

interface TopBarProps {
  scrolled: boolean;
}

export function TopBar({ scrolled }: TopBarProps) {
  const [query, setQuery] = useState("");
  const [accountMenuOpen, setAccountMenuOpen] = useState(false);
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const { user, logout } = useAuth();
  const { canGoBack, canGoForward } = useAppHistory();
  const downloadingCount = useActiveGrabCount();

  const initials =
    user?.displayName
      .split(" ")
      .map((part) => part[0])
      .slice(0, 2)
      .join("")
      .toUpperCase() ?? "";

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        inputRef.current?.focus();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, []);

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (query.trim()) {
      navigate(`/search?q=${encodeURIComponent(query.trim())}`);
    }
  }

  return (
    <header className={`topbar${scrolled ? " scrolled" : ""}`}>
      <button
        type="button"
        className="topbar-nav-btn"
        onClick={() => navigate(-1)}
        disabled={!canGoBack}
        title="Back"
      >
        <ArrowLeft size={15} />
      </button>
      <button
        type="button"
        className="topbar-nav-btn"
        onClick={() => navigate(1)}
        disabled={!canGoForward}
        title="Forward"
      >
        <ArrowRight size={15} />
      </button>

      <form className="topbar-search" onSubmit={handleSubmit} role="search">
        <MagnifyingGlass size={16} />
        <input
          ref={inputRef}
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search movies, series, anime…"
          aria-label="Search"
        />
        <span className="topbar-kbd">⌘K</span>
      </form>

      <div className="topbar-spacer" />

      {downloadingCount > 0 && (
        <span className="topbar-pill">
          <span className="dot dot-warn" />
          {downloadingCount} downloading
        </span>
      )}

      <span className="topbar-bell" title="Notifications">
        <Bell size={18} />
        <span className="topbar-bell-dot" />
      </span>

      <div className="account-menu">
        <span className="avatar" title="Account" onClick={() => setAccountMenuOpen((v) => !v)}>
          {initials}
        </span>
        {accountMenuOpen && (
          <div className="account-menu-panel" onMouseLeave={() => setAccountMenuOpen(false)}>
            <div className="account-menu-identity">
              <div className="account-menu-name">{user?.displayName}</div>
              <div className="account-menu-role">
                {user?.role === "ADMIN" ? "Admin" : "User"}
                {user?.jellyfinLinked ? " · Jellyfin" : ""}
              </div>
            </div>
            <button
              type="button"
              className="sort-menu-item"
              onClick={() => {
                setAccountMenuOpen(false);
                logout().then(() => navigate("/login"));
              }}
            >
              <SignOut size={15} />
              Sign out
            </button>
          </div>
        )}
      </div>
    </header>
  );
}

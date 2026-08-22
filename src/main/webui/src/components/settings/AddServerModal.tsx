import {
  CheckCircleIcon as CheckCircle,
  KeyIcon as Key,
  PlugsIcon as Plugs,
  WarningCircleIcon as WarningCircle,
  XIcon as X,
} from "@phosphor-icons/react";
import { useState } from "react";

/**
 * The "Add server" dialog every Jellyfin/Radarr/Sonarr section on the Servers settings page uses —
 * name/base-URL/API-key, gated on a successful test-connection before it can be saved. The three
 * were byte-for-byte identical bar labels/placeholders/which API calls to make, so this is the one
 * copy; each section just supplies its own `testConnection`/`createServer`.
 */
export function AddServerModal({
  icon,
  title,
  helpText,
  namePlaceholder,
  urlPlaceholder,
  testConnection,
  createServer,
  onClose,
  onCreated,
}: {
  icon: React.ReactNode;
  title: string;
  helpText: string;
  namePlaceholder: string;
  urlPlaceholder: string;
  testConnection: (input: { baseUrl: string; apiKey: string }) => Promise<{ ok: boolean; message: string }>;
  createServer: (input: { name: string; baseUrl: string; apiKey: string }) => Promise<unknown>;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [showKey, setShowKey] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [testedKey, setTestedKey] = useState<string | null>(null);

  // Re-keyed on every baseUrl/apiKey edit — a passing test only counts for the values it actually
  // checked, so editing either field after a successful test silently un-verifies the form again.
  const connectionKey = `${baseUrl.trim()}|${apiKey.trim()}`;
  const verified = testResult?.ok === true && testedKey === connectionKey;
  const showTestResult = testResult !== null && testedKey === connectionKey;

  const canTest = baseUrl.trim().length > 0 && apiKey.trim().length > 0 && !testing;
  const valid = name.trim().length > 0 && verified;

  async function runTest() {
    if (!canTest) return;
    setTesting(true);
    try {
      const result = await testConnection({ baseUrl: baseUrl.trim(), apiKey: apiKey.trim() });
      setTestedKey(connectionKey);
      setTestResult({ ok: result.ok, message: result.message });
    } catch (e) {
      setTestedKey(connectionKey);
      setTestResult({ ok: false, message: e instanceof Error ? e.message : "Connection failed" });
    } finally {
      setTesting(false);
    }
  }

  async function save() {
    if (!valid || saving) return;
    setSaving(true);
    setError(null);
    try {
      await createServer({ name, baseUrl, apiKey });
      onCreated();
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
          <span className="icon-tile">{icon}</span>
          <div className="dialog-header-body">
            <div className="dialog-title">{title}</div>
            <div className="dialog-sub">{helpText}</div>
          </div>
          <button type="button" className="dialog-close" onClick={onClose}>
            <X size={15} />
          </button>
        </div>

        <div className="field">
          <label>Name</label>
          <input
            className="input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={namePlaceholder}
          />
        </div>

        <div className="field">
          <label>Base URL</label>
          <input
            className="input"
            style={{ fontFamily: "var(--font-mono)" }}
            value={baseUrl}
            onChange={(e) => setBaseUrl(e.target.value)}
            placeholder={urlPlaceholder}
          />
        </div>

        <div className="field">
          <label>API key</label>
          <div style={{ position: "relative" }}>
            <Key size={14} style={{ position: "absolute", left: 12, top: 13, color: "var(--text-faint)" }} />
            <input
              className="input"
              style={{ paddingLeft: 34 }}
              type={showKey ? "text" : "password"}
              value={apiKey}
              onChange={(e) => setApiKey(e.target.value)}
            />
            <button
              type="button"
              onClick={() => setShowKey((v) => !v)}
              style={{
                position: "absolute",
                right: 10,
                top: 10,
                background: "transparent",
                border: 0,
                color: "var(--text-faint)",
                cursor: "pointer",
              }}
            >
              {showKey ? "hide" : "show"}
            </button>
          </div>
        </div>

        <div className="field">
          <button type="button" className="btn btn-secondary" onClick={runTest} disabled={!canTest}>
            <Plugs size={14} />
            {testing ? "Testing…" : "Test Connection"}
          </button>
          {showTestResult && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 7,
                marginTop: 8,
                fontSize: 12.5,
                color: testResult?.ok ? "var(--status-good-text)" : "var(--status-bad-text)",
              }}
            >
              {testResult?.ok ? <CheckCircle size={14} weight="fill" /> : <WarningCircle size={14} weight="fill" />}
              {testResult?.message}
            </div>
          )}
          {!showTestResult && (
            <p className="text-faint" style={{ fontSize: 11.5, marginTop: 8 }}>
              A successful test is required before this server can be saved.
            </p>
          )}
        </div>

        {error && <p className="text-muted">{error}</p>}

        <div className="dialog-footer">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="button" className="btn btn-hero" onClick={save} disabled={!valid || saving}>
            {saving ? "Saving…" : "Save"}
          </button>
        </div>
      </div>
    </div>
  );
}

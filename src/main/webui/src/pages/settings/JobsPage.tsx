import {
  CaretDownIcon as CaretDown,
  CheckCircleIcon as CheckCircle,
  ClockIcon as Clock,
  PencilSimpleIcon as PencilSimple,
  PlayIcon as Play,
  SpinnerIcon as Spinner,
  WarningIcon as Warning,
  XCircleIcon as XCircle,
} from "@phosphor-icons/react";
import { useState } from "react";
import { api } from "../../api/client";
import type { JobProgressEvent, JobRun, RecentJobRun, ScheduledJob } from "../../api/types";
import { JobProgressBar } from "../../components/JobProgressBar";
import { Toggle } from "../../components/Toggle";
import { useApi } from "../../hooks/useApi";
import { useJobProgress } from "../../hooks/useJobProgress";
import { relativeTime, relativeTimeUntil } from "../../utils/relativeTime";

function healthMeta(job: ScheduledJob): { kind: string; icon: JSX.Element; label: string } {
  if (job.running) return { kind: "warn", icon: <Spinner size={12} className="spin" />, label: "Running" };
  if (!job.enabled) return { kind: "neutral", icon: <Clock size={12} />, label: "Disabled" };
  if (job.lastStatus === "FAILED") return { kind: "bad", icon: <XCircle size={12} weight="fill" />, label: "Failed" };
  if (job.lastStatus === "SUCCESS") return { kind: "good", icon: <CheckCircle size={12} weight="fill" />, label: "Succeeded" };
  return { kind: "neutral", icon: <Clock size={12} />, label: "Never run" };
}

function nextRunLabel(job: ScheduledJob): string {
  if (job.running) return "running now";
  if (!job.enabled) return "not scheduled";
  if (!job.lastRunAt) return "due now";
  return relativeTimeUntil(new Date(new Date(job.lastRunAt).getTime() + job.intervalSeconds * 1000).toISOString());
}

export default function JobsPage() {
  const { data: jobs, setData: setJobs } = useApi(() => api.listJobs(), []);
  const { data: recentRuns } = useApi(() => api.listRecentJobRuns(10), []);
  const kosmosJobs = jobs?.filter((j) => j.category !== "SERVER") ?? [];
  const serverJobs = jobs?.filter((j) => j.category === "SERVER") ?? [];
  const [expandedName, setExpandedName] = useState<string | null>(null);
  const [runningName, setRunningName] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  function showToast(message: string) {
    setToast(message);
    window.setTimeout(() => setToast((c) => (c === message ? null : c)), 3600);
  }

  // Patches just the one row a mutation touched, from that mutation's own response — every other
  // row's props stay referentially unchanged, so only the row that actually changed re-renders.
  function patchJob(name: string, patch: Partial<ScheduledJob>) {
    setJobs((current) => current?.map((j) => (j.name === name ? { ...j, ...patch } : j)) ?? current);
  }

  async function runNow(job: ScheduledJob) {
    if (runningName) return;
    setRunningName(job.name);
    try {
      const run = await api.runJobNow(job.name);
      patchJob(job.name, {
        running: false,
        lastRunAt: run.startedAt,
        lastStatus: run.status,
        lastMessage: run.message,
      });
      showToast(
        run.status === "FAILED"
          ? `${job.displayName} failed${run.message ? `: ${run.message}` : ""}`
          : `${job.displayName} ran successfully`,
      );
    } catch (e) {
      showToast(e instanceof Error ? e.message : "Job run failed");
    } finally {
      setRunningName(null);
    }
  }

  async function toggleEnabled(job: ScheduledJob, enabled: boolean) {
    const updated = await api.updateJob(job.name, { enabled, intervalSeconds: job.intervalSeconds });
    patchJob(job.name, updated);
  }

  return (
    <div>
      <div className="page-header" style={{ padding: "0 0 4px" }}>
        <div style={{ flex: 1, minWidth: 280 }}>
          <h2 style={{ marginBottom: 6 }}>Jobs</h2>
          <p className="text-muted" style={{ maxWidth: "60ch" }}>
            Kosmos runs maintenance and search tasks on a schedule — automatic search for each
            library type, and polling in-flight downloads for completion. Run any of them now
            without changing its schedule, or adjust how often it runs.
          </p>
        </div>
      </div>

      {jobs?.length === 0 && <p className="text-muted">No jobs registered yet.</p>}

      {kosmosJobs.length > 0 && (
        <>
          <div className="section-label" style={{ marginTop: 8, marginBottom: 10 }}>
            Kosmos Jobs
          </div>
          {kosmosJobs.map((job) => (
            <JobRow
              key={job.id}
              job={job}
              expanded={expandedName === job.name}
              onToggleExpand={() => setExpandedName((n) => (n === job.name ? null : job.name))}
              onToggleEnabled={(enabled) => toggleEnabled(job, enabled)}
              onRunNow={() => runNow(job)}
              running={runningName === job.name}
              onSaved={(updated) => {
                patchJob(job.name, updated);
                showToast(`${job.displayName}'s interval saved`);
              }}
              onLiveUpdate={(updated) => patchJob(job.name, updated)}
            />
          ))}
        </>
      )}

      {serverJobs.length > 0 && (
        <>
          <div className="section-label" style={{ marginTop: 28, marginBottom: 10 }}>
            Server Jobs
          </div>
          {serverJobs.map((job) => (
            <JobRow
              key={job.id}
              job={job}
              expanded={expandedName === job.name}
              onToggleExpand={() => setExpandedName((n) => (n === job.name ? null : job.name))}
              onToggleEnabled={(enabled) => toggleEnabled(job, enabled)}
              onRunNow={() => runNow(job)}
              running={runningName === job.name}
              onSaved={(updated) => {
                patchJob(job.name, updated);
                showToast(`${job.displayName}'s interval saved`);
              }}
              onLiveUpdate={(updated) => patchJob(job.name, updated)}
            />
          ))}
        </>
      )}

      {recentRuns && recentRuns.length > 0 && (
        <>
          <div className="section-label" style={{ marginTop: 28, marginBottom: 10 }}>
            Recent Activity
          </div>
          <div className="history-table">
            {recentRuns.map((run) => (
              <JobRunRow key={run.id} run={run} jobDisplayName={run.jobDisplayName} />
            ))}
          </div>
        </>
      )}

      {toast && (
        <div className="toast">
          <span className="toast-icon" style={{ background: "rgba(79,191,139,.16)", color: "var(--status-good)" }}>
            <CheckCircle size={13} weight="fill" />
          </span>
          {toast}
        </div>
      )}
    </div>
  );
}

function JobRow({
  job,
  expanded,
  onToggleExpand,
  onToggleEnabled,
  onRunNow,
  running,
  onSaved,
  onLiveUpdate,
}: {
  job: ScheduledJob;
  expanded: boolean;
  onToggleExpand: () => void;
  onToggleEnabled: (enabled: boolean) => void;
  onRunNow: () => void;
  running: boolean;
  onSaved: (updated: ScheduledJob) => void;
  onLiveUpdate: (updated: ScheduledJob) => void;
}) {
  const health = healthMeta(job);
  const { data: runs, loading: runsLoading } = useApi(
    () => (expanded ? api.listJobRuns(job.name, 10) : Promise.resolve([])),
    [expanded, job.name, job.lastRunAt],
  );
  const [interval, setInterval] = useState(String(job.intervalSeconds));
  const [saving, setSaving] = useState(false);
  const [progressEvent, setProgressEvent] = useState<JobProgressEvent | null>(null);

  useJobProgress(job.name, (event) => {
    setProgressEvent(event);
    if (event.kind === "finished") {
      onLiveUpdate({
        ...job,
        running: false,
        lastStatus: event.status ?? job.lastStatus,
        lastMessage: event.message ?? job.lastMessage,
        lastRunAt: new Date().toISOString(),
      });
    }
  });

  // Ambient truth (another tab, another run trigger, or the schedule could have started this),
  // not just whether this row's own "Run Now" click is still in flight.
  const isRunning =
    running || job.running || progressEvent?.kind === "started" || progressEvent?.kind === "progress";

  async function saveInterval() {
    const parsed = Number.parseInt(interval, 10);
    if (!Number.isFinite(parsed) || parsed < 10) return;
    setSaving(true);
    try {
      const updated = await api.updateJob(job.name, { enabled: job.enabled, intervalSeconds: parsed });
      onSaved(updated);
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className={`job-row${job.enabled ? "" : " disabled"}`}>
      <div className="job-row-head" onClick={onToggleExpand}>
        <Toggle
          on={job.enabled}
          onChange={(next) => onToggleEnabled(next)}
          label={`Toggle ${job.displayName}`}
        />

        <div className="job-row-main">
          <div className="job-row-title-line">
            <span className="job-row-name">{job.displayName}</span>
          </div>
          <div className="job-row-sub">
            <span>every {job.intervalSeconds}s</span>
            <span>·</span>
            <span>last run {relativeTime(job.lastRunAt)}</span>
            <span>·</span>
            <span>{nextRunLabel(job)}</span>
            {job.lastMessage && (
              <>
                <span>·</span>
                <span>{job.lastMessage}</span>
              </>
            )}
          </div>
        </div>

        <span className={`health-pill ${health.kind}`}>
          {health.icon}
          {health.label}
        </span>

        <div className="job-row-actions">
          <button
            type="button"
            className="btn btn-secondary"
            disabled={isRunning}
            onClick={(e) => {
              e.stopPropagation();
              onRunNow();
            }}
          >
            {isRunning ? <Spinner size={14} className="spin" /> : <Play size={14} weight="fill" />}
            {isRunning ? "Running…" : "Run Now"}
          </button>
          <CaretDown size={14} className={`job-row-chevron${expanded ? " open" : ""}`} />
        </div>
      </div>

      {isRunning && (
        <div style={{ padding: "0 16px 14px" }}>
          <JobProgressBar event={progressEvent} />
        </div>
      )}

      {expanded && (
        <div className="job-row-panel">
          <div className="job-row-interval">
            <PencilSimple size={13} className="text-faint" />
            <span className="text-faint" style={{ fontSize: 12 }}>
              Interval (seconds)
            </span>
            <input
              className="input"
              value={interval}
              onChange={(e) => setInterval(e.target.value.replace(/[^0-9]/g, ""))}
            />
            <button type="button" className="btn btn-secondary" disabled={saving} onClick={saveInterval}>
              {saving ? "Saving…" : "Save"}
            </button>
          </div>

          <div className="section-label" style={{ marginBottom: 10 }}>
            Recent runs
          </div>
          {runsLoading && <p className="text-faint" style={{ fontSize: 12 }}>Loading…</p>}
          {!runsLoading && runs?.length === 0 && (
            <p className="text-faint" style={{ fontSize: 12 }}>
              This job hasn't run yet.
            </p>
          )}
          {!runsLoading && runs && runs.length > 0 && (
            <div className="history-table">
              {runs.map((run) => (
                <JobRunRow key={run.id} run={run} />
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function JobRunRow({ run, jobDisplayName }: { run: JobRun; jobDisplayName?: string }) {
  const failed = run.status === "FAILED";
  return (
    <div className="history-row" style={{ gridTemplateColumns: "auto 1fr auto auto" }}>
      <span
        className="history-row-icon"
        style={{
          background: failed ? "rgba(224,104,95,.14)" : "rgba(79,191,139,.14)",
          color: failed ? "var(--status-bad-text)" : "var(--status-good-text)",
        }}
      >
        {failed ? <Warning size={14} /> : <CheckCircle size={14} />}
      </span>
      <div style={{ minWidth: 0 }}>
        <div className="history-row-title">
          {jobDisplayName ? `${jobDisplayName} — ` : ""}
          {run.status === "SUCCESS" ? "Succeeded" : "Failed"}
        </div>
        {run.message && <div className="history-row-release">{run.message}</div>}
      </div>
      <span className="text-faint" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
        {run.finishedAt && run.startedAt
          ? `${((new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()) / 1000).toFixed(1)}s`
          : ""}
      </span>
      <span className="text-disabled" style={{ fontFamily: "var(--font-mono)", fontSize: 11.5 }}>
        {relativeTime(run.startedAt)}
      </span>
    </div>
  );
}

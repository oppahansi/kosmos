import { useEffect, useRef } from "react";

interface LibraryChangeEvent {
  contentType: string;
  mediaItemId: string;
}

/**
 * Subscribes to GET /library/changes (SSE) for as long as this component is mounted — same
 * always-on connection convention as {@link import("./useJobProgress").useJobProgress}, one per
 * mounted subscriber, no sharing. Unlike job progress, events here are debounced: a sync can fire
 * hundreds of these back-to-back, and calling `onBatch` on every single one would mean hundreds of
 * re-renders in a few seconds. Ids matching `contentTypes` are collected and flushed as one batch
 * after a short quiet window (~400ms of no new matching events, not a fixed interval — a burst
 * still resolves quickly once it stops, rather than waiting out a worst-case timer).
 */
export function useLibraryChanges(contentTypes: string[], onBatch: (mediaItemIds: string[]) => void) {
  const onBatchRef = useRef(onBatch);
  onBatchRef.current = onBatch;

  const contentTypesKey = contentTypes.join(",");

  useEffect(() => {
    const wanted = new Set(contentTypesKey.split(",").filter(Boolean));
    const pending = new Set<string>();
    let timer: number | null = null;

    const source = new EventSource("/api/library/changes");
    source.onmessage = (e) => {
      try {
        const event = JSON.parse(e.data) as LibraryChangeEvent;
        if (!wanted.has(event.contentType)) return;
        pending.add(event.mediaItemId);
        if (timer != null) window.clearTimeout(timer);
        timer = window.setTimeout(() => {
          timer = null;
          const ids = Array.from(pending);
          pending.clear();
          onBatchRef.current(ids);
        }, 400);
      } catch {
        // ignore a malformed frame
      }
    };

    return () => {
      source.close();
      if (timer != null) window.clearTimeout(timer);
    };
    // onBatch intentionally excluded — see onBatchRef; including it would reconnect on every
    // render a caller passes a fresh inline closure.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [contentTypesKey]);
}

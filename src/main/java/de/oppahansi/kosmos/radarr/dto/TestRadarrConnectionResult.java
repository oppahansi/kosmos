package de.oppahansi.kosmos.radarr.dto;

/**
 * Always a 200 — {@code ok} carries success/failure rather than an HTTP error status, since a
 * failed reachability check is an expected outcome the "Add server" modal displays inline, not a
 * server error.
 */
public record TestRadarrConnectionResult(boolean ok, String message) {}

package de.oppahansi.kosmos.sonarr.dto;

/**
 * Always a 200 — {@code ok} carries success/failure rather than an HTTP error status, since a
 * failed reachability check is an expected outcome the "Add server" modal displays inline, not a
 * server error.
 */
public record TestSonarrConnectionResult(boolean ok, String message) {}

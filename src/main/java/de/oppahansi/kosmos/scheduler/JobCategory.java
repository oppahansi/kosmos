package de.oppahansi.kosmos.scheduler;

/**
 * Which section of the Jobs settings page a job belongs to — see {@link JobHandler#category}.
 * {@code SERVER} means specifically tied to a Settings → Servers row (Jellyfin/Radarr/Sonarr sync
 * or user-import); every other job, including per-entity ones like import-list sync, is {@code
 * KOSMOS}.
 */
public enum JobCategory {
  KOSMOS,
  SERVER
}

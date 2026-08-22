package de.oppahansi.kosmos.downloads.dto;

import de.oppahansi.kosmos.downloads.Grab;
import java.time.Instant;
import java.util.UUID;

public record GrabResponse(
    UUID id,
    UUID releaseId,
    String title,
    UUID downloadClientId,
    String downloadClientName,
    String status,
    Instant grabbedAt,
    /**
     * 0-100, live from the download client — null when not GRABBED or the client is unreachable.
     */
    Double progressPercent) {

  public static GrabResponse from(Grab grab) {
    return from(grab, null);
  }

  public static GrabResponse from(Grab grab, Double progressPercent) {
    return new GrabResponse(
        grab.id,
        grab.release.id,
        grab.release.titleRaw,
        grab.downloadClient.id,
        grab.downloadClient.name,
        grab.status,
        grab.grabbedAt,
        progressPercent);
  }
}

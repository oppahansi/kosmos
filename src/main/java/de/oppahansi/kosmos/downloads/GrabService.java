package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.downloads.dto.GrabRequest;
import de.oppahansi.kosmos.indexers.Release;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.notifications.NotificationEvent;
import de.oppahansi.kosmos.notifications.NotificationEventType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class GrabService {

  @Inject BlocklistService blocklistService;
  @Inject Event<NotificationEvent> notificationEvent;

  // Magnet URIs carry their own info-hash (xt=urn:btih:<hash>) — extracting it up front lets
  // DownloadStatusPollJob correlate this Grab to a real torrent without needing addTorrent's
  // response to return one (qBittorrent's, notably, doesn't). Not every client can produce an id
  // this way though — a Usenet client has no magnet URI to parse one out of, and there jobId
  // instead comes from whatever addTorrent/addTorrentFile itself returns (see the fallback below).
  // A .torrent-file upload has neither and stays null; DownloadStatusPollJob simply never polls
  // it, so it stays GRABBED until that gap is picked up.
  private static final Pattern MAGNET_HASH = Pattern.compile("(?i)xt=urn:btih:([a-f0-9]{40})");

  @Transactional
  public Optional<Grab> grab(UUID movieId, GrabRequest request) {
    Optional<MediaItem> mediaItem = MediaItem.<MediaItem>findByIdOptional(movieId);
    if (mediaItem.isEmpty()) {
      return Optional.empty();
    }

    DownloadClient client =
        DownloadClient.<DownloadClient>findByIdOptional(request.downloadClientId())
            .orElseThrow(
                () ->
                    new BadRequestException(
                        "Unknown download client id: " + request.downloadClientId()));

    Optional<String> clientJobId = sendToClient(client, request);

    Release release = new Release();
    release.mediaItem = mediaItem.get();
    release.titleRaw = request.title();
    release.downloadUrl = request.downloadUrl();
    release.parsedResolution = request.resolution();
    release.parsedCodec = request.videoCodec();
    release.parsedSource = request.source();
    release.score = request.score();
    release.foundAt = Instant.now();
    release.persist();

    String magnetHash = extractMagnetHash(request.downloadUrl());

    Grab grab = new Grab();
    grab.release = release;
    grab.downloadClient = client;
    grab.jobId = magnetHash != null ? magnetHash : clientJobId.orElse(null);
    grab.status = "GRABBED";
    grab.grabbedAt = Instant.now();
    grab.persist();

    fireGrabbed(mediaItem.get().id, release.titleRaw);
    return Optional.of(grab);
  }

  @Transactional
  public Optional<Grab> grabFile(
      UUID movieId, UUID downloadClientId, String title, byte[] fileContent, String filename) {
    Optional<MediaItem> mediaItem = MediaItem.<MediaItem>findByIdOptional(movieId);
    if (mediaItem.isEmpty()) {
      return Optional.empty();
    }

    DownloadClient client =
        DownloadClient.<DownloadClient>findByIdOptional(downloadClientId)
            .orElseThrow(
                () -> new BadRequestException("Unknown download client id: " + downloadClientId));

    Optional<String> clientJobId = sendFileToClient(client, fileContent, filename);

    Release release = new Release();
    release.mediaItem = mediaItem.get();
    release.titleRaw = title;
    release.downloadUrl = "file:" + filename;
    release.foundAt = Instant.now();
    release.persist();

    Grab grab = new Grab();
    grab.release = release;
    grab.downloadClient = client;
    grab.jobId = clientJobId.orElse(null);
    grab.status = "GRABBED";
    grab.grabbedAt = Instant.now();
    grab.persist();

    fireGrabbed(mediaItem.get().id, release.titleRaw);
    return Optional.of(grab);
  }

  /**
   * User-driven equivalent of {@link DownloadStatusPollJob}'s own failure detection — blocklists
   * the grab's release and marks it {@code FAILED} so it stops being polled and its media item
   * becomes eligible for automatic re-search again (see {@link Grab#hasActiveGrab}), for a bad
   * download the client itself never reports as failed (stuck seeding at 0%, wrong content, etc.).
   */
  @Transactional
  public Optional<Grab> markFailed(UUID grabId) {
    Optional<Grab> grab = Grab.<Grab>findByIdOptional(grabId);
    grab.ifPresent(
        g -> {
          blocklistService.blockRelease(g.release, "Marked failed by user");
          g.status = "FAILED";
          g.failedAt = Instant.now();
        });
    return grab;
  }

  private void fireGrabbed(UUID mediaItemId, String releaseTitle) {
    notificationEvent.fire(
        new NotificationEvent(
            NotificationEventType.GRAB,
            mediaItemId,
            releaseTitle,
            "Grabbed \"" + releaseTitle + "\".",
            null));
  }

  private Optional<String> sendFileToClient(
      DownloadClient client, byte[] fileContent, String filename) {
    TorrentClient torrentClient = TorrentClients.forConfig(client);
    try {
      boolean loggedIn = torrentClient.login(client.username, client.password);
      if (!loggedIn) {
        throw badGateway(client, "login rejected");
      }
      return torrentClient.addTorrentFile(
          fileContent, filename, Optional.ofNullable(client.category));
    } catch (IOException | InterruptedException e) {
      throw badGateway(client, e.getMessage());
    }
  }

  private String extractMagnetHash(String downloadUrl) {
    Matcher matcher = MAGNET_HASH.matcher(downloadUrl);
    return matcher.find() ? matcher.group(1).toLowerCase() : null;
  }

  private Optional<String> sendToClient(DownloadClient client, GrabRequest request) {
    TorrentClient torrentClient = TorrentClients.forConfig(client);
    try {
      boolean loggedIn = torrentClient.login(client.username, client.password);
      if (!loggedIn) {
        throw badGateway(client, "login rejected");
      }
      return torrentClient.addTorrent(request.downloadUrl(), Optional.ofNullable(client.category));
    } catch (IOException | InterruptedException e) {
      throw badGateway(client, e.getMessage());
    }
  }

  private WebApplicationException badGateway(DownloadClient client, String reason) {
    return new WebApplicationException(
        "Download client \"" + client.name + "\" rejected the grab: " + reason,
        Response.Status.BAD_GATEWAY);
  }
}

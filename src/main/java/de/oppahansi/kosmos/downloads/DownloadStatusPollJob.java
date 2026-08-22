package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.library.ImportService;
import de.oppahansi.kosmos.scheduler.JobHandler;
import de.oppahansi.kosmos.scheduler.ProgressReporter;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Polls each in-flight {@link Grab} against its download client; once it completes, hands its
 * content path to {@link ImportService} and marks the Grab imported. This is what actually makes
 * {@link Grab#status} reach anything past {@code GRABBED} — see the field's own comment. Only Grabs
 * with a known {@code jobId} are pollable (see {@link GrabService} for which grabs get one). A
 * client that doesn't see the same filesystem paths Kosmos does (split-host, Docker with a
 * different bind mount) needs {@link DownloadClient#remotePath}/{@link DownloadClient#localPath}
 * configured — applied below via {@link DownloadClient#remapPath}.
 *
 * <p>A download the client itself reports failed (password-protected archive, missing files, a
 * local filesystem error — see each {@link TorrentClient}'s own {@code getTorrentInfo} for what
 * counts) is blocklisted for that media item and the Grab marked {@code FAILED} rather than left
 * {@code GRABBED} forever; the automatic-search jobs treat a media item with no still-active Grab
 * as eligible again, so this alone is what lets a bad release get retried on the next scheduled
 * search instead of quietly blocking that title forever.
 */
@ApplicationScoped
public class DownloadStatusPollJob implements JobHandler {

  @Inject ImportService importService;
  @Inject BlocklistService blocklistService;

  @Override
  public String jobName() {
    return "download-status-poll";
  }

  @Override
  public String displayName() {
    return "Download Status Poll";
  }

  @Override
  public int defaultIntervalSeconds() {
    return 30;
  }

  /**
   * Each Grab gets its own fresh transaction (rather than one for the whole batch) so one grab's
   * failure — an unreachable client, a re-imported path — can't roll back another grab's
   * already-successful status update.
   */
  @Override
  public String run(ProgressReporter progress) {
    List<Grab> pending =
        QuarkusTransaction.requiringNew()
            .call(() -> Grab.list("status = ?1 and jobId is not null", "GRABBED"));
    for (Grab grab : pending) {
      // Caught here, not just inside pollOne: an unimportable file (sample-sized, duplicate
      // path) is a normal per-grab outcome, not a job malfunction — it must never abort
      // processing of the other pending grabs in this same run, nor mark the whole job FAILED.
      try {
        QuarkusTransaction.requiringNew().run(() -> pollOne(grab.id));
      } catch (RuntimeException e) {
        // Left GRABBED; retried on the next tick.
      }
    }
    return null;
  }

  private void pollOne(UUID grabId) {
    Grab grab = Grab.<Grab>findById(grabId);
    DownloadClient client = grab.downloadClient;
    TorrentClient torrentClient = TorrentClients.forConfig(client);
    Optional<TorrentStatus> status;
    try {
      if (!torrentClient.login(client.username, client.password)) {
        return;
      }
      status = torrentClient.getTorrentInfo(grab.jobId);
    } catch (IOException | InterruptedException e) {
      return;
    }

    if (status.isEmpty()) {
      return;
    }

    if (status.get().isFailed()) {
      String reason =
          status.get().failureReason() != null
              ? status.get().failureReason()
              : "Download client reported failure";
      blocklistService.blockRelease(grab.release, reason);
      grab.status = "FAILED";
      grab.failedAt = Instant.now();
      return;
    }

    if (!status.get().isComplete()) {
      return;
    }

    String contentPath = client.remapPath(status.get().contentPath());
    if (contentPath == null || contentPath.isBlank()) {
      return;
    }

    importService.importPath(grab.release.mediaItem, contentPath);
    grab.status = "IMPORTED";
    grab.importedAt = Instant.now();
  }
}

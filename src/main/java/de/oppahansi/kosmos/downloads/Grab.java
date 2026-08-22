package de.oppahansi.kosmos.downloads;

import de.oppahansi.kosmos.common.KosmosEntity;
import de.oppahansi.kosmos.indexers.Release;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Records that a {@link Release} was sent to a {@link DownloadClient}. {@code status} starts as
 * {@code GRABBED} and moves to either {@code IMPORTED} (download completed, file landed in the
 * library) or {@code FAILED} (download client reported failure — see {@code DownloadStatusPollJob})
 * once polling resolves it.
 */
@Entity
@Table(name = "grab")
public class Grab extends KosmosEntity {

  /**
   * Whether {@code mediaItemId} has a Grab still worth waiting on — {@code GRABBED} (in flight) or
   * {@code IMPORTED} (already done, nothing to retry). A {@code FAILED} grab does *not* count:
   * that's exactly the signal the automatic-search jobs use to know a title is safe to search
   * again, so a previous failure doesn't block it forever. The shared home for this check exists
   * because {@link AutomaticSearchJob}, {@code TvAutomaticSearchJob}, and {@code
   * AnimeAutomaticSearchJob} all need the identical eligibility rule.
   */
  public static boolean hasActiveGrab(UUID mediaItemId) {
    return count("release.mediaItem.id = ?1 and status in ('GRABBED', 'IMPORTED')", mediaItemId)
        > 0;
  }

  @ManyToOne(optional = false)
  @JoinColumn(name = "release_id")
  public Release release;

  @ManyToOne(optional = false)
  @JoinColumn(name = "download_client_id")
  public DownloadClient downloadClient;

  /** Info-hash for a torrent grab, job/queue id for a Usenet one — see {@link GrabService}. */
  @Column(name = "job_id", length = 100)
  public String jobId;

  @Column(nullable = false, length = 30)
  public String status;

  @Column(name = "grabbed_at", nullable = false)
  public Instant grabbedAt;

  /** When {@code status} moved to IMPORTED/FAILED — null until then. See the class's own doc. */
  @Column(name = "imported_at")
  public Instant importedAt;

  @Column(name = "failed_at")
  public Instant failedAt;
}

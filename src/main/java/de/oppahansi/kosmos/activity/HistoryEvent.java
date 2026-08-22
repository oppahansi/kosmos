package de.oppahansi.kosmos.activity;

import de.oppahansi.kosmos.common.KosmosEntity;
import de.oppahansi.kosmos.media.MediaItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One entry in a media item's real audit trail — grabbed, imported, upgraded, failed, a file
 * deleted, a file renamed. {@link de.oppahansi.kosmos.downloads.Grab} can't retroactively become
 * this: its {@code status} is mutated in place with no {@code importedAt} of its own until now, no
 * upgrade/delete trail at all, and it's scoped to the grab/download lifecycle specifically —
 * nothing else (a sync import, a manual file delete) has anywhere to record an event either. This
 * table is the one place every kind of "something happened to this title" event lands.
 *
 * <p>{@code mediaItem} is nullable, unlinked (not cascaded away) when the title itself is deleted —
 * see {@code MediaItemDeletionService} — so the audit trail survives the title it was about, the
 * same reasoning {@code Request} already uses.
 */
@Entity
@Table(name = "history_event")
public class HistoryEvent extends KosmosEntity {

  @ManyToOne
  @JoinColumn(name = "media_item_id")
  public MediaItem mediaItem;

  /** Denormalized at write time, same convention as {@code Request.title} — see the class doc. */
  @Column(nullable = false, length = 500)
  public String title;

  /** GRABBED, IMPORTED, UPGRADED, FAILED, FILE_DELETED, RENAMED. */
  @Column(nullable = false, length = 20)
  public String kind;

  @Column(nullable = false, length = 1000)
  public String message;

  @Column(name = "size_bytes")
  public Long sizeBytes;

  @Column(name = "occurred_at", nullable = false)
  public Instant occurredAt;
}

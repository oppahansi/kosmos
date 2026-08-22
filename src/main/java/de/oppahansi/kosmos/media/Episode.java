package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.parsing.QualityProfile;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One episode of a {@link Season}, sharing its primary key with a {@link MediaItem} — same pattern
 * as {@link Movie}/{@link Show}. This is what makes an episode individually gradable/searchable:
 * {@code Release}/{@code Grab}/{@code LibraryFile} already FK to {@code media_item_id} generically,
 * so per-episode grab/import tracking needed no schema change to any of them once this landed.
 * {@code qualityProfile} isn't duplicated here — the show it belongs to already carries one, that
 * part stays whole-series granularity. {@code monitored} is per-episode, though (see the field's
 * own doc) — an unmonitored episode in an otherwise-monitored show is a real, common state (already
 * have it from elsewhere, don't care about a special/OVA, etc.), same as Sonarr's own model.
 */
@Entity
@Table(name = "episode")
public class Episode extends PanacheEntityBase {

  @Id
  @JdbcTypeCode(SqlTypes.VARCHAR)
  public UUID mediaItemId;

  @OneToOne
  @MapsId
  @JoinColumn(name = "media_item_id")
  public MediaItem mediaItem;

  @ManyToOne(optional = false)
  @JoinColumn(name = "season_id")
  public Season season;

  @Column(name = "episode_number", nullable = false)
  public int episodeNumber;

  @Column(length = 4000)
  public String overview;

  @Column(name = "air_date")
  public LocalDate airDate;

  @Column(name = "runtime_minutes")
  public Integer runtimeMinutes;

  @Column(name = "still_path", length = 500)
  public String stillPath;

  /**
   * Independent of the show's quality profile — automatic search skips an unmonitored episode of an
   * otherwise-monitored show. Defaults true so every episode a show already had before this field
   * existed keeps behaving exactly as before.
   */
  @Column(nullable = false)
  public boolean monitored = true;

  /** Convenience accessor — the show's quality profile governs what to search for this episode. */
  public QualityProfile qualityProfile() {
    return season.show.qualityProfile;
  }
}

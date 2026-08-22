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
 * One episode of an {@link AnimeSeason}, sharing its primary key with a {@link MediaItem} — same
 * pattern as {@link Episode}, and for the same reason: {@code Release}/{@code Grab}/{@code
 * LibraryFile} already FK to {@code media_item_id} generically, so this is what makes an anime
 * episode individually gradable/searchable too, with no schema change needed to any of those three
 * tables.
 *
 * <p>Carries both {@code episodeNumber} (numbering within its season) and {@code
 * absoluteEpisodeNumber} (continuous across the whole {@link Anime} franchise, the count fansub
 * releases actually use in filenames — e.g. episode 313 of a long-runner) since fansub-release
 * matching needs the latter but the season UI needs the former.
 */
@Entity
@Table(name = "anime_episode")
public class AnimeEpisode extends PanacheEntityBase {

  @Id
  @JdbcTypeCode(SqlTypes.VARCHAR)
  public UUID mediaItemId;

  @OneToOne
  @MapsId
  @JoinColumn(name = "media_item_id")
  public MediaItem mediaItem;

  @ManyToOne(optional = false)
  @JoinColumn(name = "season_id")
  public AnimeSeason season;

  @Column(name = "episode_number")
  public Integer episodeNumber;

  @Column(name = "absolute_episode_number")
  public Integer absoluteEpisodeNumber;

  @Column(name = "episode_type", nullable = false, length = 20)
  public String episodeType;

  @Column(length = 4000)
  public String overview;

  @Column(name = "air_date")
  public LocalDate airDate;

  @Column(name = "runtime_minutes")
  public Integer runtimeMinutes;

  @Column(name = "still_path", length = 500)
  public String stillPath;

  /** Same idea as {@link Episode#monitored} — independent of the anime's own quality profile. */
  @Column(nullable = false)
  public boolean monitored = true;

  /** Convenience accessor — the anime's quality profile governs what to search for this episode. */
  public QualityProfile qualityProfile() {
    return season.anime.qualityProfile;
  }
}

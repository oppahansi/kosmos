package de.oppahansi.kosmos.media;

import de.oppahansi.kosmos.downloads.Blocklist;
import de.oppahansi.kosmos.downloads.Grab;
import de.oppahansi.kosmos.downloads.PendingCandidate;
import de.oppahansi.kosmos.indexers.Release;
import de.oppahansi.kosmos.library.LibraryFile;
import de.oppahansi.kosmos.library.LibraryFileEmbeddedId;
import de.oppahansi.kosmos.library.LibraryFileHash;
import de.oppahansi.kosmos.library.LibraryFileTrack;
import de.oppahansi.kosmos.metadata.MediaItemExternalId;
import de.oppahansi.kosmos.requests.Request;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Deletes a {@link Movie}/{@link Show}/{@link Anime} and everything the schema's foreign-key graph
 * requires cleaning up first — nothing cascades at the database level (no {@code ON DELETE} clause
 * anywhere in the schema), so this is the one place that FK-deletion order lives, shared by all
 * three content types rather than re-derived per type.
 *
 * <p>Deliberately entity-hydration-free throughout (scalar {@link EntityManager} projections and
 * Panache bulk {@code delete}/{@code deleteById} calls only, never {@code find}/{@code
 * findById}/loading a managed instance) — loading a {@code LibraryFile} or {@code MediaItem} into
 * the persistence context and then bulk-deleting its underlying row out from under it leaves a
 * stale managed reference behind that Hibernate's pre-commit flush can trip over (surfaced during
 * testing as a spurious {@code TransientPropertyValueException} on an association nothing in this
 * class ever actually mutates). Working in pure bulk queries sidesteps that class of bug entirely.
 *
 * <p>{@code request} rows are never deleted, only unlinked ({@code mediaItem} set to null) — a
 * request is an audit record of what was once asked for, not owned by the title it resolved to.
 */
@ApplicationScoped
public class MediaItemDeletionService {

  @Inject EntityManager em;

  @Transactional
  public boolean deleteMovie(UUID movieId, boolean deleteFilesFromDisk) {
    if (Movie.count("mediaItemId", movieId) == 0) {
      return false;
    }
    deleteMediaItemGraph(movieId, deleteFilesFromDisk);
    Movie.deleteById(movieId);
    MediaItem.deleteById(movieId);
    return true;
  }

  @Transactional
  public boolean deleteShow(UUID showId, boolean deleteFilesFromDisk) {
    if (Show.count("mediaItemId", showId) == 0) {
      return false;
    }
    for (UUID seasonId : seasonIdsForShow(showId)) {
      for (UUID episodeId : episodeIdsForSeason(seasonId)) {
        deleteMediaItemGraph(episodeId, deleteFilesFromDisk);
        Episode.deleteById(episodeId);
        MediaItem.deleteById(episodeId);
      }
      Season.deleteById(seasonId);
    }
    deleteMediaItemGraph(showId, deleteFilesFromDisk);
    Show.deleteById(showId);
    MediaItem.deleteById(showId);
    return true;
  }

  @Transactional
  public boolean deleteAnime(UUID animeId, boolean deleteFilesFromDisk) {
    if (Anime.count("mediaItemId", animeId) == 0) {
      return false;
    }
    for (UUID seasonId : seasonIdsForAnime(animeId)) {
      for (UUID episodeId : episodeIdsForAnimeSeason(seasonId)) {
        deleteMediaItemGraph(episodeId, deleteFilesFromDisk);
        AnimeEpisode.deleteById(episodeId);
        MediaItem.deleteById(episodeId);
      }
      AnimeSeason.deleteById(seasonId);
    }
    deleteMediaItemGraph(animeId, deleteFilesFromDisk);
    Anime.deleteById(animeId);
    MediaItem.deleteById(animeId);
    return true;
  }

  /**
   * Everything that hangs off one {@code media_item} row (a movie, show, anime, or a single episode
   * — episodes share their primary key with {@code media_item}, so this applies to them
   * identically), in FK-safe order. Leaves the {@code media_item}/{@code movie}/{@code show}/{@code
   * episode} rows themselves for the caller to remove once every child is gone.
   */
  private void deleteMediaItemGraph(UUID mediaItemId, boolean deleteFilesFromDisk) {
    if (deleteFilesFromDisk) {
      libraryFilePaths(mediaItemId).forEach(this::deleteFromDisk);
    }
    LibraryFileHash.delete(
        "libraryFile.id in (select f.id from LibraryFile f where f.mediaItem.id = ?1)",
        mediaItemId);
    LibraryFileEmbeddedId.delete(
        "libraryFile.id in (select f.id from LibraryFile f where f.mediaItem.id = ?1)",
        mediaItemId);
    LibraryFileTrack.delete(
        "libraryFile.id in (select f.id from LibraryFile f where f.mediaItem.id = ?1)",
        mediaItemId);
    LibraryFile.delete("mediaItem.id", mediaItemId);

    Grab.delete(
        "release.id in (select r.id from Release r where r.mediaItem.id = ?1)", mediaItemId);
    Release.delete("mediaItem.id", mediaItemId);

    Blocklist.delete("mediaItem.id", mediaItemId);
    PendingCandidate.delete("mediaItem.id", mediaItemId);
    MediaItemExternalId.delete("mediaItem.id", mediaItemId);
    Request.update("mediaItem = null where mediaItem.id = ?1", mediaItemId);
  }

  private List<String> libraryFilePaths(UUID mediaItemId) {
    return em.createQuery(
            "select f.path from LibraryFile f where f.mediaItem.id = :id", String.class)
        .setParameter("id", mediaItemId)
        .getResultList();
  }

  private List<UUID> seasonIdsForShow(UUID showId) {
    return em.createQuery("select s.id from Season s where s.show.mediaItemId = :id", UUID.class)
        .setParameter("id", showId)
        .getResultList();
  }

  private List<UUID> episodeIdsForSeason(UUID seasonId) {
    return em.createQuery("select e.mediaItemId from Episode e where e.season.id = :id", UUID.class)
        .setParameter("id", seasonId)
        .getResultList();
  }

  private List<UUID> seasonIdsForAnime(UUID animeId) {
    return em.createQuery(
            "select s.id from AnimeSeason s where s.anime.mediaItemId = :id", UUID.class)
        .setParameter("id", animeId)
        .getResultList();
  }

  private List<UUID> episodeIdsForAnimeSeason(UUID seasonId) {
    return em.createQuery(
            "select e.mediaItemId from AnimeEpisode e where e.season.id = :id", UUID.class)
        .setParameter("id", seasonId)
        .getResultList();
  }

  /** Best-effort — a missing/permission-denied file must never abort the rest of the delete. */
  private void deleteFromDisk(String path) {
    try {
      Files.deleteIfExists(Path.of(path));
    } catch (IOException | RuntimeException e) {
      // Left on disk; the LibraryFile row referencing it is gone either way.
    }
  }
}

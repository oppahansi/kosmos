package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.media.MediaItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import java.io.File;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class LibraryRootFolderService {

  @ConfigProperty(name = "kosmos.library.root-path")
  Optional<String> configuredRootPath;

  public List<LibraryRootFolder> listAll() {
    return LibraryRootFolder.list("ORDER BY createdAt");
  }

  public Optional<LibraryRootFolder> findById(UUID id) {
    return LibraryRootFolder.findByIdOptional(id);
  }

  public LibraryRootFolder resolveOrThrow(UUID id) {
    return findById(id).orElseThrow(() -> new BadRequestException("Unknown root folder id: " + id));
  }

  /**
   * The registered root folder a given on-disk file path actually lives under, if any — used when a
   * source (e.g. Jellyfin) reports a file's real path directly, so the item lands under the correct
   * folder rather than whatever {@link #getDefault} would guess when several exist (e.g. "movies"
   * vs "anime-movies"). Longest matching prefix wins, so a more specific folder registered inside a
   * broader one is preferred.
   */
  public Optional<LibraryRootFolder> findContaining(String filePath) {
    if (filePath == null) {
      return Optional.empty();
    }
    return listAll().stream()
        .filter(folder -> isUnder(filePath, folder.path))
        .max((a, b) -> Integer.compare(a.path.length(), b.path.length()));
  }

  private boolean isUnder(String filePath, String folderPath) {
    String normalizedFolder = folderPath.endsWith("/") ? folderPath : folderPath + "/";
    return filePath.startsWith(normalizedFolder);
  }

  /** Used at title-creation time: an explicit choice if given, else {@link #getDefault(String)}. */
  @Transactional
  public Optional<LibraryRootFolder> resolveOrDefault(UUID rootFolderId, String contentType) {
    return rootFolderId != null ? findById(rootFolderId) : getDefault(contentType);
  }

  public Optional<LibraryRootFolder> getDefault() {
    return getDefault(null);
  }

  /**
   * Prefers a folder tagged for {@code contentType} (or untagged, meaning "accepts anything") over
   * one tagged for something else; falls back to the oldest folder regardless, or a folder seeded
   * from the legacy kosmos.library.root-path env var on first boot if none exist yet at all.
   */
  @Transactional
  public Optional<LibraryRootFolder> getDefault(String contentType) {
    List<LibraryRootFolder> all = listAll();
    if (contentType != null) {
      Optional<LibraryRootFolder> matching =
          all.stream().filter(folder -> accepts(folder, contentType)).findFirst();
      if (matching.isPresent()) {
        return matching;
      }
    }
    if (!all.isEmpty()) {
      return Optional.of(all.get(0));
    }
    return configuredRootPath
        .filter(path -> !path.isBlank())
        .map(path -> createInternal(path, null));
  }

  /**
   * Read-only variant of {@link #getDefault(String)} for callers that must never trigger its
   * legacy-env-var auto-create fallback as a side effect (e.g. a health check run on every job
   * tick) — just "is there a folder {@code contentType} could land in right now."
   */
  public boolean hasFolderAccepting(String contentType) {
    return listAll().stream().anyMatch(folder -> accepts(folder, contentType));
  }

  private boolean accepts(LibraryRootFolder folder, String contentType) {
    if (folder.contentTypes == null || folder.contentTypes.isBlank()) {
      return true;
    }
    return Arrays.asList(folder.contentTypes.split(",")).contains(contentType);
  }

  /**
   * Registers a folder without verifying Kosmos's own process can see it — used when the source
   * (e.g. Jellyfin) has already vouched for the path, since it may run on a different host/mount
   * than Kosmos does. Silently no-ops on a blank path or one already registered, rather than
   * erroring — callers doing this in bulk shouldn't have to handle either as a real failure.
   */
  @Transactional
  public Optional<LibraryRootFolder> createTrusted(String path, List<String> contentTypes) {
    if (path == null || path.isBlank()) {
      return Optional.empty();
    }
    if (LibraryRootFolder.find("path", path).firstResultOptional().isPresent()) {
      return Optional.empty();
    }
    return Optional.of(createInternal(path, contentTypes));
  }

  @Transactional
  public LibraryRootFolder create(String path, List<String> contentTypes) {
    if (path == null || path.isBlank()) {
      throw new BadRequestException("Path is required");
    }
    if (!new File(path).isDirectory()) {
      throw new BadRequestException("Not a directory Kosmos can see: " + path);
    }
    if (LibraryRootFolder.find("path", path).firstResultOptional().isPresent()) {
      throw new BadRequestException("Already registered: " + path);
    }
    return createInternal(path, contentTypes);
  }

  private LibraryRootFolder createInternal(String path, List<String> contentTypes) {
    LibraryRootFolder folder = new LibraryRootFolder();
    folder.path = path;
    folder.contentTypes =
        contentTypes == null || contentTypes.isEmpty() ? null : String.join(",", contentTypes);
    folder.createdAt = Instant.now();
    folder.persist();
    return folder;
  }

  @Transactional
  public void delete(UUID id) {
    LibraryRootFolder folder =
        findById(id).orElseThrow(() -> new NotFoundException("Unknown root folder id: " + id));
    long inUse = MediaItem.count("rootFolder", folder);
    if (inUse > 0) {
      throw new BadRequestException(
          "Still used by " + inUse + " title" + (inUse == 1 ? "" : "s") + " — move them first");
    }
    folder.delete();
  }
}

package de.oppahansi.kosmos.library;

import de.oppahansi.kosmos.library.dto.CommitImportRequest;
import de.oppahansi.kosmos.library.dto.CommitImportResult;
import de.oppahansi.kosmos.library.dto.ImportCandidate;
import de.oppahansi.kosmos.library.dto.ImportScanRequest;
import de.oppahansi.kosmos.media.MediaItem;
import de.oppahansi.kosmos.scheduler.TaskRunner;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;

/**
 * The "review everything unmatched" import path — {@link ImportResource}'s single-file,
 * single-known-movie endpoint stays as the quick-attach path it always was; this is for pointing at
 * a whole download-client output folder, seeing every file it found with a best-effort match (see
 * {@link ImportMatchService}), and committing a reviewed/overridden batch in one call.
 */
@Path("/import")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BulkImportResource {

  @Inject ImportMatchService importMatchService;
  @Inject ImportService importService;
  @Inject TaskRunner taskRunner;

  @POST
  @Path("/scan")
  public List<ImportCandidate> scan(ImportScanRequest request) {
    return importMatchService.scan(request.sourcePath());
  }

  /**
   * Each item imports (and, on failure, fails) independently — {@link ImportService#importPath} is
   * itself transactional per call, so one bad path in a batch never touches the rest. Runs through
   * {@link TaskRunner} purely for Jobs-page visibility (a real {@link
   * de.oppahansi.kosmos.scheduler.JobRun} row) — still fully synchronous within the request, same
   * as before; the response shape is unchanged.
   */
  @POST
  @Path("/commit")
  public List<CommitImportResult> commit(CommitImportRequest request) {
    List<CommitImportResult> results = new ArrayList<>();
    taskRunner.run(
        "bulk-import",
        "Bulk Import",
        () -> {
          request.items().forEach(item -> results.add(commitOne(item)));
          long ok = results.stream().filter(CommitImportResult::success).count();
          return ok + "/" + results.size() + " imported";
        });
    return results;
  }

  private CommitImportResult commitOne(CommitImportRequest.Item item) {
    MediaItem mediaItem = MediaItem.<MediaItem>findByIdOptional(item.mediaItemId()).orElse(null);
    if (mediaItem == null) {
      return CommitImportResult.failed(item.sourcePath(), "Unknown media item id");
    }
    try {
      LibraryFile file = importService.importPath(mediaItem, item.sourcePath());
      return CommitImportResult.ok(item.sourcePath(), file.id);
    } catch (RuntimeException e) {
      return CommitImportResult.failed(item.sourcePath(), e.getMessage());
    }
  }
}

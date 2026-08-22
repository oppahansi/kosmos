package de.oppahansi.kosmos.activity;

import de.oppahansi.kosmos.activity.dto.ActivityHistoryItem;
import de.oppahansi.kosmos.activity.dto.ActivityStats;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Backs the Activity page's history feed. The "queue" half of that page (in-flight downloads) is
 * {@code GrabsResource}'s existing {@code GET /grabs} — reused as-is rather than duplicated here,
 * since it already returns every {@link de.oppahansi.kosmos.downloads.Grab} with live progress.
 */
@Path("/activity")
@Produces(MediaType.APPLICATION_JSON)
public class ActivityResource {

  private static final int DEFAULT_LIMIT = 200;

  @Inject HistoryEventService historyEventService;

  @GET
  @Path("/history")
  public List<ActivityHistoryItem> history(@QueryParam("limit") Integer limit) {
    return historyEventService.recent(limit != null ? limit : DEFAULT_LIMIT).stream()
        .map(ActivityHistoryItem::from)
        .toList();
  }

  /** Real today's counts, not capped by whatever page size {@link #history} was fetched with. */
  @GET
  @Path("/stats")
  public ActivityStats stats() {
    Instant startOfDay = Instant.now().truncatedTo(ChronoUnit.DAYS);
    return new ActivityStats(
        historyEventService.countSince("IMPORTED", startOfDay),
        historyEventService.countSince("FAILED", startOfDay));
  }
}

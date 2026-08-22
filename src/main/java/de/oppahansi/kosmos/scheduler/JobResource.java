package de.oppahansi.kosmos.scheduler;

import de.oppahansi.kosmos.scheduler.dto.JobProgressEvent;
import de.oppahansi.kosmos.scheduler.dto.JobRunResponse;
import de.oppahansi.kosmos.scheduler.dto.RecentJobRunResponse;
import de.oppahansi.kosmos.scheduler.dto.ScheduledJobResponse;
import de.oppahansi.kosmos.scheduler.dto.UpdateScheduledJobRequest;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Multi;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.jboss.resteasy.reactive.RestStreamElementType;

@Path("/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class JobResource {

  private static final int DEFAULT_RUN_HISTORY = 20;

  @Inject JobService jobService;

  @GET
  public List<ScheduledJobResponse> list() {
    return jobService.listAll().stream().map(ScheduledJobResponse::from).toList();
  }

  /**
   * Live progress for one job — SSE, not polling. A late subscriber (opened mid-run) gets the last
   * known event replayed immediately, so the settings UI isn't blind until the next update happens
   * to fire; see {@link JobProgressBroadcaster}.
   *
   * <p>{@code @Blocking}: a {@code Multi}-returning endpoint runs on the I/O thread by default,
   * including everything ahead of it in the filter chain — {@link
   * de.oppahansi.kosmos.auth.SessionFilter} does a blocking Hibernate lookup on every request,
   * which blows up there. This dispatches the whole request to a worker thread instead; the
   * returned {@code Multi} still streams asynchronously once subscribed.
   */
  @GET
  @Path("/{name}/progress")
  @Produces(MediaType.SERVER_SENT_EVENTS)
  @RestStreamElementType(MediaType.APPLICATION_JSON)
  @Blocking
  public Multi<JobProgressEvent> progress(@PathParam("name") String name) {
    return jobService.streamProgress(name);
  }

  /** Most recent runs first — the history a job's expanded row on the settings page reads. */
  @GET
  @Path("/{name}/runs")
  public List<JobRunResponse> runs(
      @PathParam("name") String name, @QueryParam("limit") Integer limit) {
    return jobService.runsFor(name, limit == null ? DEFAULT_RUN_HISTORY : limit).stream()
        .map(JobRunResponse::from)
        .toList();
  }

  /**
   * Most recent runs across every job — scheduled and one-off ({@code TaskRunner}) alike — the
   * settings page's global "Recent Activity" section. A distinct route from {@link #runs}, not a
   * collision: {@code /jobs/runs} is one path segment, {@code /jobs/{name}/runs} is two.
   */
  @GET
  @Path("/runs")
  public List<RecentJobRunResponse> recentRuns(@QueryParam("limit") Integer limit) {
    return jobService.recentRuns(limit == null ? 10 : limit).stream()
        .map(RecentJobRunResponse::from)
        .toList();
  }

  /**
   * Triggers the job immediately, same as Seerr's "Run Now" — bypasses the interval but not
   * enabled/already-running. Runs synchronously (matches {@code POST /jellyfin-servers/{id}/sync}'s
   * existing convention for a user-triggered background operation) — every current job handler
   * finishes in well under a request timeout.
   */
  @POST
  @Path("/{name}/run")
  public Response runNow(@PathParam("name") String name) {
    if (!jobService.handlerExists(name)) {
      return Response.status(Response.Status.NOT_FOUND).build();
    }
    return jobService
        .runNow(name)
        .map(run -> Response.ok(JobRunResponse.from(run)).build())
        .orElse(Response.status(Response.Status.CONFLICT).build());
  }

  @PUT
  @Path("/{name}")
  public Response update(@PathParam("name") String name, UpdateScheduledJobRequest request) {
    if (request.intervalSeconds() < 10) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity("intervalSeconds must be at least 10")
          .build();
    }
    return jobService
        .update(name, request.enabled(), request.intervalSeconds())
        .map(job -> Response.ok(ScheduledJobResponse.from(job)).build())
        .orElse(Response.status(Response.Status.NOT_FOUND).build());
  }
}

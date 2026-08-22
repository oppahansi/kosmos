package de.oppahansi.kosmos.scheduler;

import java.util.function.Supplier;

/**
 * The one piece of logic genuinely shared between a scheduled run ({@link JobRunner}) and a one-off
 * task ({@link TaskRunner}): run the body, map a thrown exception to a FAILED outcome instead of
 * letting it propagate, map a normal return to SUCCESS. Everything before (claiming a schedule slot
 * vs. nothing) and after (updating a {@link ScheduledJob} row vs. nothing) differs between the two,
 * so only this part is worth factoring out.
 */
final class JobRunOutcome {

  record Result(String status, String message) {}

  static Result of(Supplier<String> body) {
    try {
      return new Result("SUCCESS", body.get());
    } catch (RuntimeException e) {
      return new Result("FAILED", e.getMessage());
    }
  }

  private JobRunOutcome() {}
}

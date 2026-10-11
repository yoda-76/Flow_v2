package com.flow.orderflow;

import com.flow.journal.Json;

import java.util.List;

/**
 * One model's evaluation of one idea at one moment (extended Q6.1-Q6.4, gapAnswers §5). Status OK carries a score
 * -2..+2 with the shared meaning: +2 full setup incl. closed trigger bar, +1 location + read without trigger,
 * 0 location without reads, -1 a read against the idea, -2 model invalidation. NO_SIGNAL = location absent,
 * NO_DATA = an input is not available. Never a numeric 0 for missing data.
 */
public record ModelResult(String model, Status status, int score, List<String> reads, Integer stopRefTicks,
                          Integer locLowTicks, Integer locHighTicks, String note) {
  public enum Status { OK, NO_SIGNAL, NO_DATA }

  public static ModelResult noSignal(String model, String note) {
    return new ModelResult(model, Status.NO_SIGNAL, 0, List.of(), null, null, null, note);
  }

  public static ModelResult noData(String model, String note) {
    return new ModelResult(model, Status.NO_DATA, 0, List.of(), null, null, null, note);
  }

  public boolean isFullSetup() { return status == Status.OK && score >= 2; }
  public boolean isVeto() { return status == Status.OK && score <= -2; }

  public String toJson() {
    Json j = Json.object().field("m", model).field("st", status.name());
    if (status == Status.OK) j.field("score", score);
    j.field("reads", String.join(",", reads));
    j.fieldOrNull("stopRef", stopRefTicks).fieldOrNull("locLo", locLowTicks).fieldOrNull("locHi", locHighTicks);
    if (note != null) j.field("note", note);
    return j.build();
  }
}

import { describe, expect, test } from "bun:test";
import {
  GATE_TIMING,
  gateWakeAt,
  type LoadGate,
  startGate,
  stepGate,
} from "./load-gate.ts";

const { delayMs, minShownMs, timeoutMs } = GATE_TIMING;

/** The gate after each of `steps`, a busy flag at a time, from the app opening at 0. */
function run(steps: [boolean, number][]): LoadGate {
  let gate = startGate(0);
  for (const [busy, now] of steps) {
    gate = stepGate(gate, busy, now);
  }
  return gate;
}

describe("the loading screen", () => {
  test("holds the person from the start without the logo", () => {
    const gate = startGate(0);
    expect(gate.phase).toBe("waiting");
    expect(gateWakeAt(gate)).toBe(delayMs);
  });

  test("an instant load never shows the logo", () => {
    expect(run([[true, 50]]).phase).toBe("waiting");
    expect(
      run([
        [true, 50],
        [false, 120],
      ]).phase,
    ).toBe("open");
  });

  test("a slow load shows the logo, then lets the person in", () => {
    const shown = run([[true, delayMs]]);
    expect(shown.phase).toBe("shown");
    expect(shown.shownSince).toBe(delayMs);
    const done = stepGate(shown, false, delayMs + minShownMs + 1);
    expect(done.phase).toBe("open");
    expect(gateWakeAt(done)).toBeUndefined();
  });

  test("the logo stays its shortest time before letting the person in", () => {
    const early = run([
      [true, delayMs],
      [false, delayMs + 100],
    ]);
    expect(early.phase).toBe("shown");
    expect(gateWakeAt(early)).toBe(delayMs + minShownMs);
    expect(stepGate(early, false, delayMs + minShownMs).phase).toBe("open");
  });

  test("a load that runs too long lets the person in and stays out of the way", () => {
    const late = run([
      [true, delayMs],
      [true, timeoutMs],
    ]);
    expect(late.phase).toBe("open");
    expect(late.timedOut).toBe(true);
    expect(gateWakeAt(late)).toBeUndefined();
    expect(stepGate(late, true, timeoutMs * 2).phase).toBe("open");
  });

  test("a new load after the last one ended starts over", () => {
    const later = run([
      [false, 10],
      [true, 1000],
    ]);
    expect(later.phase).toBe("waiting");
    expect(later.busySince).toBe(1000);
    expect(stepGate(later, true, 1000 + delayMs).phase).toBe("shown");
  });

  test("a load that timed out and ended lets the next one hold again", () => {
    const next = run([
      [true, timeoutMs],
      [false, timeoutMs + 1],
      [true, timeoutMs + 2],
    ]);
    expect(next.phase).toBe("waiting");
    expect(next.timedOut).toBe(false);
  });

  test("wakes for the logo, then for the time limit", () => {
    expect(gateWakeAt(run([[true, 10]]))).toBe(delayMs);
    expect(gateWakeAt(run([[true, delayMs]]))).toBe(timeoutMs);
  });
});

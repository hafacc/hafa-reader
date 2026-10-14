/**
 * Whether the app is held behind its loading screen: `open` lets the person
 * in, `waiting` holds them behind a blank screen for a moment, so a quick load
 * shows no logo, and `shown` holds them behind the turning logo.
 */
export type GatePhase = "open" | "waiting" | "shown";

/** How long the loading screen waits, stays and holds out. */
export interface GateTiming {
  /** How long a load runs before the logo shows. */
  delayMs: number;
  /** Shortest time the logo stays once shown, so it never flickers. */
  minShownMs: number;
  /** Longest time a load holds the person; after it they are let in and the load goes on behind. */
  timeoutMs: number;
}

/** The loading screen's timing in the app. */
export const GATE_TIMING: GateTiming = {
  delayMs: 300,
  minShownMs: 700,
  timeoutMs: 15_000,
};

/** The loading screen's state; see `stepGate`. */
export interface LoadGate {
  /** What the screen shows now. */
  readonly phase: GatePhase;
  /** When the current load began; undefined when nothing is loading. */
  readonly busySince: number | undefined;
  /** When the logo appeared; undefined when it is not shown. */
  readonly shownSince: number | undefined;
  /** Whether the current load ran out of time, so it goes on without holding anyone. */
  readonly timedOut: boolean;
}

const OPEN: LoadGate = {
  phase: "open",
  busySince: undefined,
  shownSince: undefined,
  timedOut: false,
};

/** A loading screen whose load begins at `now`, as when the app opens. */
export function startGate(now: number): LoadGate {
  return { ...OPEN, phase: "waiting", busySince: now };
}

/**
 * The loading screen at `now`, with `busy` saying whether something that can
 * still change the lists is loading.
 *
 * A load holds the person behind a blank screen at first, and behind the
 * turning logo once it has run `delayMs`; an instant load shows no logo. Once
 * shown, the logo stays at least `minShownMs`. A load still running after
 * `timeoutMs` lets the person in and holds no one until it ends; the next one
 * starts over.
 */
export function stepGate(
  gate: LoadGate,
  busy: boolean,
  now: number,
  timing: GateTiming = GATE_TIMING,
): LoadGate {
  let next: LoadGate;
  if (!busy) {
    const holding =
      gate.phase === "shown" &&
      gate.shownSince !== undefined &&
      now < gate.shownSince + timing.minShownMs;
    next = holding ? { ...gate, busySince: undefined, timedOut: false } : OPEN;
  } else if (gate.timedOut) {
    next = { ...gate, phase: "open", shownSince: undefined };
  } else {
    const since = gate.busySince ?? now;
    if (now - since >= timing.timeoutMs) {
      next = {
        phase: "open",
        busySince: since,
        shownSince: undefined,
        timedOut: true,
      };
    } else if (gate.phase === "shown") {
      next = { ...gate, busySince: since };
    } else if (now - since >= timing.delayMs) {
      next = { ...OPEN, phase: "shown", busySince: since, shownSince: now };
    } else {
      next = { ...OPEN, phase: "waiting", busySince: since };
    }
  }
  return next;
}

/** When the loading screen next changes on its own, for a timer; undefined when it will not. */
export function gateWakeAt(
  gate: LoadGate,
  timing: GateTiming = GATE_TIMING,
): number | undefined {
  const times: number[] = [];
  if (gate.busySince !== undefined && !gate.timedOut) {
    times.push(gate.busySince + timing.timeoutMs);
    if (gate.phase === "waiting") {
      times.push(gate.busySince + timing.delayMs);
    }
  }
  // a load that ended while the logo was up lets the person in once it has stayed long enough
  if (
    gate.phase === "shown" &&
    gate.busySince === undefined &&
    gate.shownSince !== undefined
  ) {
    times.push(gate.shownSince + timing.minShownMs);
  }
  return times.length > 0 ? Math.min(...times) : undefined;
}

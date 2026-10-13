import { expect, test } from "bun:test";
import { computeCase, type ScheduleFixture } from "./schedule-fixture.ts";

// Numbers match within this share of their size, or this much near zero.
const RELATIVE_TOLERANCE = 1e-9;
const ABSOLUTE_TOLERANCE = 1e-12;

function mismatch(actual: unknown, expected: unknown, path: string): string {
  let found = "";
  if (typeof expected === "number" && typeof actual === "number") {
    const allowed = Math.max(
      ABSOLUTE_TOLERANCE,
      RELATIVE_TOLERANCE * Math.abs(expected),
    );
    found =
      Math.abs(actual - expected) <= allowed
        ? ""
        : `${path}: ${actual} != ${expected}`;
  } else if (Array.isArray(expected) && Array.isArray(actual)) {
    found =
      actual.length === expected.length
        ? (expected
            .map((item, index) =>
              mismatch(actual[index], item, `${path}[${index}]`),
            )
            .find((message) => message !== "") ?? "")
        : `${path}: length ${actual.length} != ${expected.length}`;
  } else if (
    typeof expected === "object" &&
    expected !== null &&
    typeof actual === "object" &&
    actual !== null
  ) {
    const keys = new Set([...Object.keys(expected), ...Object.keys(actual)]);
    found =
      [...keys]
        .map((key) =>
          mismatch(
            Reflect.get(actual, key),
            Reflect.get(expected, key),
            `${path}.${key}`,
          ),
        )
        .find((message) => message !== "") ?? "";
  } else {
    found = Object.is(actual, expected)
      ? ""
      : `${path}: ${String(actual)} != ${String(expected)}`;
  }
  return found;
}

test("the schedule matches every case in the shared fixture", async () => {
  const fixture: ScheduleFixture = await Bun.file(
    new URL("../fixtures/schedule.json", import.meta.url),
  ).json();
  expect(fixture.version).toBe(1);
  const problems: string[] = [];
  for (const [section, cases] of Object.entries(fixture.sections)) {
    for (const [index, { input, output }] of cases.entries()) {
      const actual = JSON.parse(JSON.stringify(computeCase(section, input)));
      const problem = mismatch(actual, output, `${section}[${index}]`);
      if (problem !== "") {
        problems.push(problem);
      }
    }
  }
  expect(problems).toEqual([]);
  expect(Object.keys(fixture.sections).length).toBeGreaterThan(10);
});

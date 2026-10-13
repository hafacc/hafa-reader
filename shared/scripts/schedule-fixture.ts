/** Regenerate `fixtures/schedule.json` from the TypeScript schedule. */
import { buildScheduleFixture } from "../src/schedule-fixture.ts";

const path = new URL("../fixtures/schedule.json", import.meta.url);
await Bun.write(path, `${JSON.stringify(buildScheduleFixture(), null, 2)}\n`);

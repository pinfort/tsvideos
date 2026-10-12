import { describe, expect, it } from "vitest";
import { parseProgramQuery, programQueryParams } from "./program-query";

describe("parseProgramQuery", () => {
  it("uses UI defaults and allows blank optional filters", () => {
    expect(parseProgramQuery({})).toMatchObject({
      limit: 10,
      offset: 0,
      sort: "RECORDED_AT",
      direction: "DESC",
    });
    expect(
      parseProgramQuery({ minDrops: "", recordedFrom: "", status: "" }),
    ).toMatchObject({
      minDrops: undefined,
      recordedFrom: undefined,
      status: undefined,
    });
  });
  it("round trips every combined filter with zero values and local timestamps", () => {
    const raw = {
      name: "番組 & 特集",
      channel: "BS1",
      recordedFrom: "2026-10-01T00:00",
      recordedTo: "2026-11-01T00:00",
      status: "COMPLETED",
      minDrops: "0",
      maxDrops: "2",
      fileType: "ENCODED",
      sort: "SIZE",
      direction: "ASC",
      limit: "500",
      offset: "20",
    };
    expect(
      Object.fromEntries(programQueryParams(parseProgramQuery(raw))),
    ).toEqual(raw);
  });
  it.each([
    { limit: "0" },
    { limit: "501" },
    { limit: "1.5" },
    { limit: "NaN" },
    { offset: "-1" },
    { offset: "2147483648" },
    { minDrops: "-1" },
    { minDrops: "3", maxDrops: "2" },
    { status: "bad" },
    { fileType: "bad" },
    { sort: "bad" },
    { direction: "bad" },
    { recordedFrom: "2026-02-30T12:00" },
    { recordedFrom: "2026-10-12T25:00" },
    { recordedFrom: "2026-10-12T00:00Z" },
    { recordedFrom: "2026-10-12T00:00:00.0", recordedTo: "2026-10-12T00:00" },
    { name: ["a", "b"] },
  ])(
    "rejects invalid input %j rather than silently searching a different range",
    (raw) => {
      expect(() => parseProgramQuery(raw)).toThrow();
    },
  );
  it("supports leap days and compares subsecond local bounds without timezone conversion", () => {
    expect(
      parseProgramQuery({
        recordedFrom: "2024-02-29T00:00:00.000000001",
        recordedTo: "2024-02-29T00:00:00.000000002",
      }).recordedFrom,
    ).toBe("2024-02-29T00:00:00.000000001");
  });
});

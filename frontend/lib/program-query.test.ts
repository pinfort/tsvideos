import { describe, expect, it } from "vitest";
import { parseProgramQuery } from "./program-query";

describe("parseProgramQuery", () => {
  it("uses defaults for missing and non-finite values", () => {
    expect(parseProgramQuery({})).toEqual({ name: "", limit: 10, offset: 0 });
    expect(parseProgramQuery({ limit: "NaN", offset: "Infinity" })).toEqual({ name: "", limit: 10, offset: 0 });
  });
  it("truncates fractions and preserves the search term", () => {
    expect(parseProgramQuery({ name: "番組", limit: "2.8", offset: "3.9" })).toEqual({ name: "番組", limit: 2, offset: 3 });
  });
  it("keeps pagination within the backend integer bounds", () => {
    expect(parseProgramQuery({ limit: "500", offset: "1e30" })).toEqual({ name: "", limit: 100, offset: 2_147_483_647 });
    expect(parseProgramQuery({ limit: "-5", offset: "-2" })).toEqual({ name: "", limit: 1, offset: 0 });
  });
});

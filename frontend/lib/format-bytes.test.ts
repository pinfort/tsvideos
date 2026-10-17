import { describe, expect, it } from "vitest";
import { formatBytes } from "./format-bytes";

describe("formatBytes", () => {
  it("groups digits and labels the unit", () => {
    expect(formatBytes(3000000000)).toBe("3,000,000,000 bytes");
    expect(formatBytes(0)).toBe("0 bytes");
  });

  it("labels null as unverified instead of zero", () => {
    expect(formatBytes(null)).toBe("不明（未検証）");
  });
});

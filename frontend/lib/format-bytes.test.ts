import { describe, expect, it } from "vitest";
import { formatBytes } from "./format-bytes";

describe("formatBytes", () => {
  it("uses base-1024 units with at most one fractional digit", () => {
    expect(formatBytes(3000000000)).toBe("2.8 GiB");
    expect(formatBytes(1536)).toBe("1.5 KiB");
    expect(formatBytes(1024 ** 4 * 3)).toBe("3 TiB");
  });

  it("keeps zero and values below 1024 in bytes", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(1)).toBe("1 B");
    expect(formatBytes(1023)).toBe("1023 B");
  });

  it("switches units exactly at each power of 1024 and omits a trailing zero", () => {
    expect(formatBytes(1024)).toBe("1 KiB");
    expect(formatBytes(1024 ** 2)).toBe("1 MiB");
    expect(formatBytes(1024 ** 3)).toBe("1 GiB");
    expect(formatBytes(1024 ** 4)).toBe("1 TiB");
    expect(formatBytes(1024 ** 5)).toBe("1 PiB");
  });

  it("promotes values that would round up to 1024 of the lower unit", () => {
    expect(formatBytes(1024 ** 2 - 1)).toBe("1 MiB");
    expect(formatBytes(1024 ** 3 - 1)).toBe("1 GiB");
    expect(formatBytes(1024 ** 5 - 1)).toBe("1 PiB");
    expect(formatBytes(1023.9 * 1024)).toBe("1023.9 KiB");
  });

  it("formats large values up to Number.MAX_SAFE_INTEGER in PiB", () => {
    expect(formatBytes(Number.MAX_SAFE_INTEGER)).toBe("8 PiB");
    expect(formatBytes(1024 ** 5 * 1500)).toBe("1500 PiB");
  });

  it("labels null as unverified instead of zero", () => {
    expect(formatBytes(null)).toBe("不明（未検証）");
  });
});

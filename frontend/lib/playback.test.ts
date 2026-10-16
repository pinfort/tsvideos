import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  PLAYBACK_KEY,
  readPlayback,
  savePlayback,
  removePlayback,
  completed,
  parsePositiveId,
  playbackTitle,
} from "./playback";
const entry = {
  videoId: 1,
  programId: 4,
  title: "番組",
  position: 20,
  duration: 200,
  updatedAt: 1,
};
beforeEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});
describe("browser playback storage", () => {
  it("persists separate videos and clears completed entries", () => {
    savePlayback(entry);
    savePlayback({ ...entry, videoId: 2, position: 40, updatedAt: 2 });
    expect(readPlayback().map((e) => e.videoId)).toEqual([2, 1]);
    savePlayback({ ...entry, position: 191 });
    expect(readPlayback().map((e) => e.videoId)).toEqual([2]);
    removePlayback(2);
    expect(readPlayback()).toEqual([]);
  });
  it("ignores malformed storage and invalid entries", () => {
    localStorage.setItem(PLAYBACK_KEY, "{");
    expect(readPlayback()).toEqual([]);
    localStorage.setItem(
      PLAYBACK_KEY,
      JSON.stringify([
        entry,
        { ...entry, videoId: -1 },
        { ...entry, position: -3 },
        null,
      ]),
    );
    expect(readPlayback()).toEqual([entry]);
  });
  it("tolerates disabled storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("disabled");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("disabled");
    });
    expect(readPlayback()).toEqual([]);
    expect(() => savePlayback(entry)).not.toThrow();
  });
  it("parses positive IDs and view titles", () => {
    expect(parsePositiveId("42")).toBe(42);
    for (const raw of ["0", "-1", "1.2", "abc", "", "9007199254740992", 42, undefined, ["1"]])
      expect(parsePositiveId(raw)).toBeUndefined();
    expect(playbackTitle("番組", 3)).toBe("番組");
    expect(playbackTitle("  ", 3)).toBe("動画 #3");
    expect(playbackTitle(["a"], 3)).toBe("動画 #3");
    expect(playbackTitle("x".repeat(301), 3)).toHaveLength(300);
  });
  it("bounds history and defines completion", () => {
    for (let i = 1; i <= 55; i++)
      savePlayback({ ...entry, videoId: i, updatedAt: i });
    expect(readPlayback()).toHaveLength(50);
    expect(completed(95, 100)).toBe(true);
    expect(completed(89, 100)).toBe(false);
    expect(completed(950, 1000)).toBe(true);
  });
});

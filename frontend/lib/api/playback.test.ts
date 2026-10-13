import { afterEach, describe, expect, it, vi } from "vitest";
import { getPlaybackProgramDetail } from "./playback";
afterEach(() => vi.unstubAllGlobals());
describe("playback metadata client", () => {
  it("uses the same-origin metadata endpoint", async () => {
    const detail = { program: { id: 1 }, videoFiles: [] };
    const fetch = vi
      .fn()
      .mockResolvedValue({ ok: true, status: 200, json: async () => detail });
    vi.stubGlobal("fetch", fetch);
    expect(await getPlaybackProgramDetail(1)).toEqual(detail);
    expect(fetch).toHaveBeenCalledWith("/api/playback/programs/1", {
      cache: "no-store",
    });
  });
  it("returns null only for confirmed not found", async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, status: 404 });
    vi.stubGlobal("fetch", fetch);
    expect(await getPlaybackProgramDetail(1)).toBeNull();
    fetch.mockResolvedValue({ ok: false, status: 502 });
    await expect(getPlaybackProgramDetail(1)).rejects.toMatchObject({
      status: 502,
    });
  });
});

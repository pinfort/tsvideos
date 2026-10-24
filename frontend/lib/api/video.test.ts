import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "./client";

const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));

vi.mock("./client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./client")>();
  return { ...actual, fetchJson };
});

import { getVideoChapters, getVideoStreamUrl, VIDEO_CHAPTERS_TIMEOUT_MS } from "./video";

describe("getVideoStreamUrl", () => {
  const originalEnv = process.env.NEXT_PUBLIC_API_BASE_URL;

  afterEach(() => {
    if (originalEnv === undefined) {
      delete process.env.NEXT_PUBLIC_API_BASE_URL;
    } else {
      process.env.NEXT_PUBLIC_API_BASE_URL = originalEnv;
    }
  });

  it("builds the stream URL from the configured API base", () => {
    process.env.NEXT_PUBLIC_API_BASE_URL = "https://api.example.com";
    expect(getVideoStreamUrl(7)).toBe("https://api.example.com/api/v1/video/7/stream");
  });

  it("falls back to localhost when unset", () => {
    delete process.env.NEXT_PUBLIC_API_BASE_URL;
    expect(getVideoStreamUrl(7)).toBe("http://localhost:8080/api/v1/video/7/stream");
  });
});

describe("getVideoChapters", () => {
  beforeEach(() => {
    fetchJson.mockReset();
  });

  it("fetches the chapters of the video", async () => {
    const response = {
      probed: true,
      chapters: [{ index: 0, startSeconds: 0, endSeconds: 5.739, title: "A" }],
    };
    fetchJson.mockResolvedValue(response);
    expect(await getVideoChapters(7)).toEqual(response);
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/video/7/chapters", {
      signal: expect.any(AbortSignal),
    });
  });

  it("propagates API errors such as 404", async () => {
    fetchJson.mockRejectedValue(new ApiError(404, "not found"));
    await expect(getVideoChapters(7)).rejects.toBeInstanceOf(ApiError);
  });

  it("aborts the request once the timeout elapses", async () => {
    const controller = new AbortController();
    const timeout = vi.spyOn(AbortSignal, "timeout").mockReturnValue(controller.signal);
    try {
      fetchJson.mockImplementation(
        (_path: string, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal!.addEventListener("abort", () => reject(init.signal!.reason));
          }),
      );
      const request = getVideoChapters(7);
      expect(timeout).toHaveBeenCalledWith(VIDEO_CHAPTERS_TIMEOUT_MS);
      controller.abort(new DOMException("timed out", "TimeoutError"));
      await expect(request).rejects.toMatchObject({ name: "TimeoutError" });
    } finally {
      timeout.mockRestore();
    }
  });
});

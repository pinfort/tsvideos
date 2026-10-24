import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";

const { notFound, getVideoChapters } = vi.hoisted(() => ({
  notFound: vi.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
  getVideoChapters: vi.fn(),
}));
vi.mock("next/navigation", () => ({ notFound }));
vi.mock("@/lib/api/video", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/video")>();
  return { ...actual, getVideoChapters };
});

import VideoViewPage from "./page";

describe("VideoViewPage", () => {
  beforeEach(() => {
    getVideoChapters.mockReset();
    getVideoChapters.mockResolvedValue({ probed: false, chapters: [] });
  });

  it("passes probed chapters to the player", async () => {
    getVideoChapters.mockResolvedValue({
      probed: true,
      chapters: [
        { index: 0, startSeconds: 0, endSeconds: 5, title: "A" },
        { index: 1, startSeconds: 5, endSeconds: 9, title: "A" },
      ],
    });
    render(await VideoViewPage({ params: Promise.resolve({ id: "42" }) }));
    expect(getVideoChapters).toHaveBeenCalledWith(42);
    expect(screen.getByRole("navigation", { name: "チャプタ" })).toBeInTheDocument();
    expect(screen.getAllByText("A")).toHaveLength(2);
  });

  it("shows no chapters, but still the player, when they are unavailable", async () => {
    for (const outcome of [
      () => getVideoChapters.mockResolvedValue({ probed: false, chapters: [] }),
      () => getVideoChapters.mockResolvedValue({ probed: true, chapters: [] }),
      () => getVideoChapters.mockRejectedValue(new ApiError(404, "not found")),
      () => getVideoChapters.mockRejectedValue(new TypeError("fetch failed")),
      () =>
        getVideoChapters.mockRejectedValue(new DOMException("timed out", "TimeoutError")),
      () => getVideoChapters.mockRejectedValue(new DOMException("aborted", "AbortError")),
    ]) {
      outcome();
      const { container, unmount } = render(
        await VideoViewPage({ params: Promise.resolve({ id: "42" }) }),
      );
      expect(container.querySelector("video")).toHaveAttribute(
        "src",
        "http://localhost:8080/api/v1/video/42/stream",
      );
      expect(screen.queryByRole("navigation", { name: "チャプタ" })).toBeNull();
      unmount();
    }
  });

  it("calls notFound for a non-numeric id", async () => {
    await expect(VideoViewPage({ params: Promise.resolve({ id: "abc" }) })).rejects.toThrow(
      "NEXT_NOT_FOUND",
    );
  });

  it("renders a video element pointing at the stream URL", async () => {
    const jsx = await VideoViewPage({ params: Promise.resolve({ id: "42" }) });
    const { container } = render(jsx);

    const video = container.querySelector("video");
    expect(video).toHaveAttribute("src", "http://localhost:8080/api/v1/video/42/stream");
  });

  it("calls notFound for a zero or unsafe id", async () => {
    for (const id of ["0", "9007199254740992"]) {
      await expect(VideoViewPage({ params: Promise.resolve({ id }) })).rejects.toThrow(
        "NEXT_NOT_FOUND",
      );
    }
  });

  it("uses a default title when none is given", async () => {
    render(await VideoViewPage({ params: Promise.resolve({ id: "42" }) }));
    expect(screen.getByRole("heading")).toHaveTextContent("動画 #42");
  });

  it("caps the title and ignores an invalid programId", async () => {
    const title = "あ".repeat(400);
    render(
      await VideoViewPage({
        params: Promise.resolve({ id: "42" }),
        searchParams: Promise.resolve({ programId: "abc", title }),
      }),
    );
    expect(screen.getByRole("heading").textContent).toBe(title.slice(0, 300));

    const video = document.querySelector("video")!;
    video.currentTime = 30;
    Object.defineProperty(video, "duration", { configurable: true, value: 200 });
    video.dispatchEvent(new Event("loadedmetadata"));
    video.dispatchEvent(new Event("pause"));
    const saved = JSON.parse(localStorage.getItem("tsvideos.playback.v1")!);
    expect(saved[0]).not.toHaveProperty("programId");
    localStorage.clear();
  });
});

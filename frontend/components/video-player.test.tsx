import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { VideoPlayer } from "./video-player";
import { readPlayback, savePlayback } from "@/lib/playback";
beforeEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});
function setup(duration = 200) {
  const view = render(<VideoPlayer videoId={1} programId={4} title="番組" />);
  const video = view.container.querySelector("video")!;
  Object.defineProperty(video, "duration", {
    configurable: true,
    value: duration,
  });
  fireEvent.loadedMetadata(video);
  return video;
}
describe("VideoPlayer", () => {
  it("restores position and supports speed/restart", () => {
    savePlayback({
      videoId: 1,
      title: "番組",
      position: 40,
      duration: 200,
      updatedAt: 1,
    });
    const video = setup();
    expect(video.currentTime).toBe(40);
    fireEvent.change(screen.getByLabelText("再生速度"), {
      target: { value: "1.5" },
    });
    expect(video.playbackRate).toBe(1.5);
    fireEvent.click(screen.getByText("最初から再生"));
    expect(video.currentTime).toBe(0);
    expect(readPlayback()).toEqual([]);
  });
  it("saves pause/seek and clears completion", () => {
    const video = setup();
    video.currentTime = 30;
    fireEvent.pause(video);
    expect(readPlayback()[0].position).toBe(30);
    video.currentTime = 80;
    fireEvent.seeked(video);
    expect(readPlayback()[0].position).toBe(80);
    video.currentTime = 200;
    fireEvent.ended(video);
    expect(readPlayback()).toEqual([]);
  });
  it("rejects saved position beyond shortened duration", () => {
    savePlayback({
      videoId: 1,
      title: "番組",
      position: 150,
      duration: 200,
      updatedAt: 1,
    });
    expect(setup(100).currentTime).toBe(0);
    expect(readPlayback()).toEqual([]);
  });
  it("shows recoverable errors and removes history", () => {
    const video = setup();
    video.currentTime = 20;
    fireEvent.pause(video);
    fireEvent.error(video);
    expect(screen.getByRole("alert")).toHaveTextContent("動画を再生できません");
    fireEvent.click(screen.getByText("履歴から削除"));
    expect(readPlayback()).toEqual([]);
  });
  it("handles metadata and errors already present before hydration", () => {
    // jsdom has no MediaError getter to spy on, so define one and always remove it.
    Object.defineProperty(HTMLMediaElement.prototype, "error", {
      configurable: true,
      get: () => ({ code: 4 }),
    });
    try {
      const first = render(<VideoPlayer videoId={1} title="番組" />);
      expect(screen.getByRole("alert")).toBeInTheDocument();
      first.unmount();
    } finally {
      Reflect.deleteProperty(HTMLMediaElement.prototype, "error");
    }
    vi.spyOn(HTMLMediaElement.prototype, "readyState", "get").mockReturnValue(
      1,
    );
    vi.spyOn(HTMLMediaElement.prototype, "duration", "get").mockReturnValue(
      200,
    );
    savePlayback({
      videoId: 1,
      title: "番組",
      position: 40,
      duration: 200,
      updatedAt: 1,
    });
    const second = render(<VideoPlayer videoId={1} title="番組" />);
    expect(second.container.querySelector("video")!.currentTime).toBe(40);
  });
  it("does not rewrite history for the restore seek", () => {
    savePlayback({
      videoId: 1,
      title: "番組",
      position: 40,
      duration: 200,
      updatedAt: 1,
    });
    const video = setup();
    fireEvent.seeked(video);
    expect(readPlayback()[0].updatedAt).toBe(1);
    video.currentTime = 80;
    fireEvent.seeked(video);
    expect(readPlayback()[0].position).toBe(80);
  });
  it("persists a user seek even if the restore seek fired no event", () => {
    savePlayback({
      videoId: 1,
      title: "番組",
      position: 40,
      duration: 200,
      updatedAt: 1,
    });
    const video = setup();
    video.currentTime = 120;
    fireEvent.seeked(video);
    expect(readPlayback()[0].position).toBe(120);
  });
  it("saves the current position on unmount", () => {
    const view = render(<VideoPlayer videoId={1} programId={4} title="番組" />);
    const video = view.container.querySelector("video")!;
    Object.defineProperty(video, "duration", { configurable: true, value: 200 });
    fireEvent.loadedMetadata(video);
    video.currentTime = 60;
    view.unmount();
    expect(readPlayback()[0]).toMatchObject({ position: 60, programId: 4 });
  });
  it("throttles progress writes and flushes on page hide", () => {
    let now = 10000;
    vi.spyOn(Date, "now").mockImplementation(() => now);
    const video = setup();
    video.currentTime = 20;
    fireEvent.timeUpdate(video);
    now += 1000;
    video.currentTime = 25;
    fireEvent.timeUpdate(video);
    expect(readPlayback()[0].position).toBe(20);
    now += 4000;
    fireEvent.timeUpdate(video);
    expect(readPlayback()[0].position).toBe(25);
    video.currentTime = 30;
    fireEvent(window, new Event("pagehide"));
    expect(readPlayback()[0].position).toBe(30);
    video.currentTime = 35;
    vi.spyOn(document, "visibilityState", "get").mockReturnValue("hidden");
    fireEvent(document, new Event("visibilitychange"));
    expect(readPlayback()[0].position).toBe(35);
  });
});

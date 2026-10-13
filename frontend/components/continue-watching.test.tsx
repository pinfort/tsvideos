import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ContinueWatching } from "./continue-watching";
import { readPlayback, savePlayback } from "@/lib/playback";
import { getPlaybackProgramDetail } from "@/lib/api/playback";
vi.mock("@/lib/api/playback", () => ({ getPlaybackProgramDetail: vi.fn() }));
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});
const entry = {
  videoId: 1,
  programId: 4,
  title: "番組",
  position: 20,
  duration: 200,
  updatedAt: 1,
};
describe("ContinueWatching", () => {
  it("renders no empty section", () => {
    render(<ContinueWatching />);
    expect(screen.queryByRole("region")).not.toBeInTheDocument();
    expect(getPlaybackProgramDetail).not.toHaveBeenCalled();
  });
  it("marks deleted recordings and removes history", async () => {
    vi.mocked(getPlaybackProgramDetail).mockResolvedValue(null);
    savePlayback(entry);
    render(<ContinueWatching />);
    expect(
      await screen.findByText("番組（削除済み・利用不可）"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "番組を履歴から削除" }));
    expect(readPlayback()).toEqual([]);
  });
  it("distinguishes offline checks from deletion", async () => {
    vi.mocked(getPlaybackProgramDetail).mockRejectedValue(new Error("offline"));
    savePlayback(entry);
    render(<ContinueWatching />);
    expect(await screen.findByText("利用可否未確認")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute(
      "href",
      expect.stringContaining("programId=4"),
    );
  });
  it("supports direct links and cross-tab deletion", async () => {
    savePlayback({ ...entry, programId: undefined });
    render(<ContinueWatching />);
    expect(await screen.findByRole("link")).toHaveTextContent("番組");
    expect(getPlaybackProgramDetail).not.toHaveBeenCalled();
    localStorage.clear();
    fireEvent(window, new StorageEvent("storage", { key: null }));
    await waitFor(() =>
      expect(screen.queryByRole("link")).not.toBeInTheDocument(),
    );
  });
});

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import {
  currentChapterIndex,
  formatChapterTime,
  VideoChapters,
} from "./video-chapters";
import type { VideoChapter } from "@/lib/api/types";

const chapters: VideoChapter[] = [
  { index: 0, startSeconds: 0, endSeconds: 5.739, title: "A" },
  { index: 1, startSeconds: 5.739, endSeconds: 65, title: "A" },
  { index: 2, startSeconds: 65, endSeconds: 95, title: "CM" },
  { index: 3, startSeconds: 95, endSeconds: 3725.5, title: null },
  { index: 4, startSeconds: 3725.5, endSeconds: 3730, title: "CM?" },
];

describe("formatChapterTime", () => {
  it("formats h:mm:ss, rounding down", () => {
    expect(formatChapterTime(0)).toBe("0:00:00");
    expect(formatChapterTime(5.739)).toBe("0:00:05");
    expect(formatChapterTime(65)).toBe("0:01:05");
    expect(formatChapterTime(3725.5)).toBe("1:02:05");
    expect(formatChapterTime(36000)).toBe("10:00:00");
  });
});

describe("currentChapterIndex", () => {
  it("finds the chapter that has started and not ended", () => {
    expect(currentChapterIndex(chapters, 0)).toBe(0);
    expect(currentChapterIndex(chapters, 5.739)).toBe(1);
    expect(currentChapterIndex(chapters, 70)).toBe(2);
    expect(currentChapterIndex(chapters, 3729.9)).toBe(4);
  });

  it("is null in a gap, past the end or without chapters", () => {
    const gapped: VideoChapter[] = [
      { index: 0, startSeconds: 0, endSeconds: 10, title: "A" },
      { index: 1, startSeconds: 20, endSeconds: 30, title: "B" },
    ];
    expect(currentChapterIndex(gapped, 15)).toBeNull();
    expect(currentChapterIndex(gapped, 30)).toBeNull();
    expect(currentChapterIndex([], 5)).toBeNull();
  });
});

describe("VideoChapters", () => {
  it("renders nothing without chapters", () => {
    const { container } = render(
      <VideoChapters chapters={[]} currentIndex={null} onSelect={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("lists start time and title, numbering untitled chapters, with duplicate titles kept", () => {
    render(
      <VideoChapters chapters={chapters} currentIndex={null} onSelect={() => {}} />,
    );
    const buttons = screen.getAllByRole("button");
    expect(buttons.map((b) => b.textContent)).toEqual([
      "0:00:00A",
      "0:00:05A",
      "0:01:05CM",
      "0:01:35チャプタ 4",
      "1:02:05CM?",
    ]);
    expect(screen.getByRole("navigation", { name: "チャプタ" })).toBeInTheDocument();
  });

  it("marks only the current chapter with aria-current", () => {
    render(<VideoChapters chapters={chapters} currentIndex={1} onSelect={() => {}} />);
    const buttons = screen.getAllByRole("button");
    expect(buttons[1]).toHaveAttribute("aria-current", "true");
    expect(buttons.filter((b) => b.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("dims CM chapters only", () => {
    render(<VideoChapters chapters={chapters} currentIndex={null} onSelect={() => {}} />);
    expect(
      screen.getAllByRole("button").map((b) => b.getAttribute("data-kind")),
    ).toEqual(["main", "main", "cm", "main", "cm"]);
  });

  it("reports the clicked chapter", () => {
    const onSelect = vi.fn();
    render(<VideoChapters chapters={chapters} currentIndex={null} onSelect={onSelect} />);
    fireEvent.click(screen.getByText("チャプタ 4"));
    expect(onSelect).toHaveBeenCalledWith(chapters[3]);
  });

  it("ignores clicks while disabled", () => {
    const onSelect = vi.fn();
    render(
      <VideoChapters chapters={chapters} currentIndex={null} onSelect={onSelect} disabled />,
    );
    expect(screen.getAllByRole("button").every((b) => b.hasAttribute("disabled"))).toBe(true);
    fireEvent.click(screen.getByText("チャプタ 4"));
    expect(onSelect).not.toHaveBeenCalled();
  });
});

import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Program } from "@/lib/api/types";

const { getPrograms } = vi.hoisted(() => ({ getPrograms: vi.fn() }));
vi.mock("@/lib/api/programs", () => ({ getPrograms }));

import HomePage from "./page";

function makeProgram(id: number): Program {
  return {
    id,
    name: `Program ${id}`,
    executedFileId: id,
    status: "COMPLETED",
    drops: 0,
    size: 0,
    recordedAt: "2024-01-01T00:00:00Z",
    channel: "1",
    title: `Program ${id}`,
    channelName: "Channel",
    duration: 0,
  };
}

describe("HomePage", () => {
  beforeEach(() => {
    getPrograms.mockReset();
  });

  it("defaults to limit 10 offset 0 and fetches one extra row to probe for a next page", async () => {
    getPrograms.mockResolvedValue({ programs: [makeProgram(1), makeProgram(2)] });

    const jsx = await HomePage({ searchParams: Promise.resolve({}) });
    render(jsx);

    expect(getPrograms).toHaveBeenCalledWith({ name: "", limit: 11, offset: 0 });
    expect(screen.getByText("Program 1")).toBeInTheDocument();
    expect(screen.getByText("Program 2")).toBeInTheDocument();
    expect(screen.getByText(">>")).not.toHaveAttribute("href");
  });

  it("links to the storage overview", async () => {
    getPrograms.mockResolvedValue({ programs: [] });

    render(await HomePage({ searchParams: Promise.resolve({}) }));

    expect(screen.getByRole("link", { name: "ストレージ概要" })).toHaveAttribute("href", "/storage");
  });

  it("clamps an out-of-range limit to the backend's max of 100", async () => {
    getPrograms.mockResolvedValue({ programs: [] });

    const jsx = await HomePage({ searchParams: Promise.resolve({ limit: "500" }) });
    render(jsx);

    // 100 is already the max, so no +1 probe row is requested.
    expect(getPrograms).toHaveBeenCalledWith({ name: "", limit: 100, offset: 0 });
  });

  it("clamps a non-numeric limit to the default of 10", async () => {
    getPrograms.mockResolvedValue({ programs: [] });

    const jsx = await HomePage({ searchParams: Promise.resolve({ limit: "not-a-number" }) });
    render(jsx);

    expect(getPrograms).toHaveBeenCalledWith({ name: "", limit: 11, offset: 0 });
  });

  it("clamps a negative offset to zero", async () => {
    getPrograms.mockResolvedValue({ programs: [] });

    const jsx = await HomePage({ searchParams: Promise.resolve({ offset: "-5" }) });
    render(jsx);

    expect(getPrograms).toHaveBeenCalledWith({ name: "", limit: 11, offset: 0 });
  });

  it("detects a next page when the extra probe row comes back and trims it from the rendered list", async () => {
    getPrograms.mockResolvedValue({ programs: Array.from({ length: 11 }, (_, i) => makeProgram(i)) });

    const jsx = await HomePage({ searchParams: Promise.resolve({ limit: "10" }) });
    render(jsx);

    expect(screen.getByText(">>")).toHaveAttribute("href", "/?limit=10&offset=10");
    expect(screen.getAllByRole("row")).toHaveLength(11); // header + 10 trimmed rows, not 11
  });
});

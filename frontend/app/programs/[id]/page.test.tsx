import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ProgramDetailResponse } from "@/lib/api/types";

const { getProgramDetail, notFound } = vi.hoisted(() => ({
  getProgramDetail: vi.fn(),
  notFound: vi.fn(() => {
    throw new Error("NEXT_NOT_FOUND");
  }),
}));
vi.mock("@/lib/api/programs", () => ({ getProgramDetail }));
vi.mock("next/navigation", () => ({ notFound }));

import ProgramDetailPage from "./page";

const detail: ProgramDetailResponse = {
  program: {
    id: 1,
    name: "Detail Program",
    executedFileId: 10,
    status: "COMPLETED",
    drops: 0,
    size: 0,
    recordedAt: "2024-01-01T00:00:00Z",
    channel: "1",
    title: "Detail Program",
    channelName: "Channel",
    duration: 0,
    createdFiles: [],
    tags: [],
    checks: [],
  },
  videoFiles: [],
};

describe("ProgramDetailPage", () => {
  beforeEach(() => {
    getProgramDetail.mockReset();
    notFound.mockClear();
  });

  it("calls notFound for a non-numeric id without hitting the API", async () => {
    await expect(ProgramDetailPage({ params: Promise.resolve({ id: "abc" }) })).rejects.toThrow(
      "NEXT_NOT_FOUND",
    );
    expect(getProgramDetail).not.toHaveBeenCalled();
  });

  it("calls notFound when the program doesn't exist", async () => {
    getProgramDetail.mockResolvedValue(null);
    await expect(ProgramDetailPage({ params: Promise.resolve({ id: "1" }) })).rejects.toThrow(
      "NEXT_NOT_FOUND",
    );
    expect(getProgramDetail).toHaveBeenCalledWith(1);
  });

  it("renders the program detail and its video files when found", async () => {
    getProgramDetail.mockResolvedValue(detail);
    const jsx = await ProgramDetailPage({ params: Promise.resolve({ id: "1" }) });
    render(jsx);
    expect(screen.getByText("Detail Program")).toBeInTheDocument();
  });
});

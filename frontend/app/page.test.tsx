vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("@/lib/api/organization", () => ({
  getOrganizationOptions: async () => ({ collections: [], tags: [] }),
  getProgramOrganization: async () => ({ collections: [], tags: [] }),
}));
import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Program } from "@/lib/api/types";
import { ApiError } from "@/lib/api/client";
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
    recordedAt: "2024-01-01T00:00:00",
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
  it("requests the exact page size and uses total rather than an extra-row heuristic", async () => {
    getPrograms.mockResolvedValue({
      programs: [makeProgram(1)],
      total: 11,
      limit: 10,
      offset: 0,
    });
    render(await HomePage({ searchParams: Promise.resolve({}) }));
    expect(getPrograms).toHaveBeenCalledWith(
      expect.objectContaining({
        limit: 10,
        offset: 0,
        sort: "RECORDED_AT",
        direction: "DESC",
      }),
    );
    expect(screen.getByText("Program 1")).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "次のページ" }),
    ).toBeInTheDocument();
  });
  it("supports the maximum page size and a clear empty result", async () => {
    getPrograms.mockResolvedValue({
      programs: [],
      total: 0,
      limit: 500,
      offset: 0,
    });
    render(
      await HomePage({
        searchParams: Promise.resolve({
          limit: "500",
          channel: "BS1",
          maxDrops: "0",
        }),
      }),
    );
    expect(getPrograms).toHaveBeenCalledWith(
      expect.objectContaining({ limit: 500, channel: "BS1", maxDrops: 0 }),
    );
    expect(
      screen.getByText(/条件に一致する録画がありません/),
    ).toBeInTheDocument();
  });
  it("keeps invalid input editable and does not call the API", async () => {
    render(
      await HomePage({
        searchParams: Promise.resolve({ minDrops: "3", maxDrops: "2" }),
      }),
    );
    expect(screen.getByRole("alert")).toHaveTextContent(
      "ドロップ数の下限は上限以下",
    );
    expect(screen.getByLabelText("ドロップ数（下限）")).toHaveValue(3);
    expect(getPrograms).not.toHaveBeenCalled();
  });
  it.each([new ApiError(400, "private details"), new Error("secret")])(
    "shows a safe actionable API error",
    async (error) => {
      getPrograms.mockRejectedValue(error);
      render(await HomePage({ searchParams: Promise.resolve({}) }));
      expect(screen.getByRole("alert")).toHaveTextContent(/検索/);
      expect(screen.getByRole("alert")).not.toHaveTextContent(/secret|private/);
    },
  );
});

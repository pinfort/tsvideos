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
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));

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

  it("uses the default page size when limit is submitted empty", async () => {
    getPrograms.mockResolvedValue({ programs: [], total: 0, limit: 10, offset: 0 });

    render(await HomePage({ searchParams: Promise.resolve({ limit: "" }) }));

    expect(getPrograms).toHaveBeenCalledWith(
      expect.objectContaining({ limit: 10 }),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByText("詳細検索").closest("details")).not.toHaveAttribute("open");
  });

  it("links to the storage overview", async () => {
    getPrograms.mockResolvedValue({ programs: [], total: 0, limit: 10, offset: 0 });

    render(await HomePage({ searchParams: Promise.resolve({}) }));

    expect(screen.getByRole("link", { name: "ストレージ概要" })).toHaveAttribute("href", "/storage");
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

  it.each([{ limit: "501" }, { minDrops: "-1" }])(
    "shows server validation errors for numeric constraints: %o",
    async (params) => {
      render(await HomePage({ searchParams: Promise.resolve(params) }));
      expect(screen.getByRole("alert")).toHaveTextContent("整数で指定してください");
      expect(screen.getByText(/^詳細検索/).closest("details")).toHaveAttribute("open");
      expect(screen.getByLabelText("1ページの件数")).toBeVisible();
      expect(getPrograms).not.toHaveBeenCalled();
    },
  );

  it("opens invalid date range inputs for correction without calling the API", async () => {
    render(
      await HomePage({
        searchParams: Promise.resolve({
          recordedFrom: "2026-10-02T12:00",
          recordedTo: "2026-10-01T12:00",
        }),
      }),
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "録画開始日時は終了日時より前にしてください。",
    );
    expect(screen.getByText(/^詳細検索/).closest("details")).toHaveAttribute("open");
    expect(screen.getByLabelText("録画開始（この日時以降）")).toBeVisible();
    expect(screen.getByLabelText("録画開始（この日時以降）")).toHaveValue(
      "2026-10-02T12:00",
    );
    expect(screen.getByLabelText("録画終了（この日時を含まない）")).toHaveValue(
      "2026-10-01T12:00",
    );
    expect(getPrograms).not.toHaveBeenCalled();
  });

  it("rejects repeated query params instead of silently picking one", async () => {
    render(
      await HomePage({
        searchParams: Promise.resolve({ name: ["a", "b"] }),
      }),
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "同じ検索条件を複数指定できません",
    );
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

import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { LibraryHealth } from "@/lib/api/health";
import { ApiError } from "@/lib/api/client";
const { refreshLibraryHealth } = vi.hoisted(() => ({ refreshLibraryHealth: vi.fn() }));
vi.mock("@/lib/api/health", () => ({ refreshLibraryHealth }));
import { LibraryHealthDashboard } from "./library-health-dashboard";
const empty: LibraryHealth = {
  failedPrograms: [],
  droppedPrograms: [],
  latestRun: null,
  lastCompletedRun: null,
  findings: [],
  summary: null,
};
const run = {
  id: 1,
  state: "COMPLETED" as const,
  startedAt: "2026-01-01T00:00:00Z",
  completedAt: "2026-01-01T00:01:00Z",
  failureCode: null,
};
const completed: LibraryHealth = {
  ...empty,
  latestRun: run,
  lastCompletedRun: run,
  summary: { VERIFIED: 0, MISSING: 1, SIZE_MISMATCH: 0, INACCESSIBLE: 1, UNCHECKED: 0 },
  findings: [
    {
      programName: "番組",
      programRemoved: false,
      finding: {
        programId: 1,
        fileId: 2,
        file: "a",
        status: "MISSING",
        checkedAt: run.completedAt,
        expectedBytes: 10,
        actualBytes: null,
        reason: null,
      },
    },
    {
      programName: null,
      programRemoved: true,
      finding: {
        programId: 9,
        fileId: 3,
        file: "b",
        status: "INACCESSIBLE",
        checkedAt: run.completedAt,
        expectedBytes: 10,
        actualBytes: null,
        reason: null,
      },
    },
  ],
};
beforeEach(() => {
  refreshLibraryHealth.mockReset();
});
describe("LibraryHealthDashboard", () => {
  it("no prior verification is unchecked and mounting never scans", () => {
    render(<LibraryHealthDashboard initial={empty} />);
    expect(screen.getByText(/完了した検証はまだありません/)).toBeInTheDocument();
    expect(screen.getAllByText("未検証").length).toBeGreaterThan(1);
    expect(refreshLibraryHealth).not.toHaveBeenCalled();
  });
  it("shows failure/drop detail links and separates inaccessible from confirmed missing", () => {
    render(
      <LibraryHealthDashboard
        initial={{
          ...completed,
          failedPrograms: [{ id: 1, name: "失敗録画", status: "ERROR", drops: null }],
          droppedPrograms: [{ id: 2, name: "ドロップ録画", status: "COMPLETED", drops: 5 }],
        }}
      />,
    );
    expect(screen.getByRole("link", { name: "失敗録画" })).toHaveAttribute("href", "/programs/1");
    expect(screen.getByRole("link", { name: "ドロップ録画" })).toHaveAttribute(
      "href",
      "/programs/2",
    );
    expect(screen.getAllByText("ファイル欠落")).toHaveLength(2);
    expect(screen.getAllByText("NAS に接続・アクセス不可")).toHaveLength(2);
    expect(screen.getByText("削除済み番組 #9")).not.toHaveAttribute("href");
    expect(screen.getByText(/最終検証完了 UTC/)).toHaveTextContent(run.completedAt);
    expect(screen.getByText(/分前/)).toBeInTheDocument();
  });
  it("failed latest run retains previous completed findings", () => {
    render(
      <LibraryHealthDashboard
        initial={{
          ...completed,
          latestRun: { ...run, id: 2, state: "FAILED", failureCode: "VERIFICATION_FAILED" },
        }}
      />,
    );
    expect(screen.getByRole("alert")).toHaveTextContent("前回完了した結果");
    expect(screen.getByRole("link", { name: "番組" })).toBeInTheDocument();
  });
  it("active run blocks another scan while state refresh only reads", async () => {
    const user = userEvent.setup();
    refreshLibraryHealth.mockResolvedValue(completed);
    render(
      <LibraryHealthDashboard
        initial={{ ...empty, latestRun: { ...run, state: "RUNNING", completedAt: null } }}
      />,
    );
    expect(screen.getByRole("button", { name: "NAS の検証を実行" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "状態を再読み込み" }));
    expect(refreshLibraryHealth).toHaveBeenCalledWith(false);
    await screen.findByRole("link", { name: "番組" });
  });
  it("scan is explicit, shows pending, then updates saved result", async () => {
    const user = userEvent.setup();
    let resolve!: (value: LibraryHealth) => void;
    refreshLibraryHealth.mockReturnValue(
      new Promise<LibraryHealth>((done) => {
        resolve = done;
      }),
    );
    render(<LibraryHealthDashboard initial={empty} />);
    await user.click(screen.getByRole("button", { name: "NAS の検証を実行" }));
    expect(refreshLibraryHealth).toHaveBeenCalledWith(true);
    expect(screen.getByRole("status")).toHaveTextContent("処理中");
    expect(screen.getByRole("button", { name: "NAS の検証を実行" })).toBeDisabled();
    resolve(completed);
    await screen.findByRole("link", { name: "番組" });
  });
  it("conflict and fetch failure preserve existing results without leaked errors", async () => {
    const user = userEvent.setup();
    refreshLibraryHealth.mockRejectedValue(new ApiError(409, "secret"));
    render(<LibraryHealthDashboard initial={completed} />);
    await user.click(screen.getByRole("button", { name: "NAS の検証を実行" }));
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("別の検証が実行中"));
    expect(screen.getByRole("link", { name: "番組" })).toBeInTheDocument();
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
  });
  it("initial unavailable is not a healthy zero state", () => {
    render(<LibraryHealthDashboard initial={null} />);
    expect(screen.getByRole("alert")).toHaveTextContent("取得できませんでした");
    expect(screen.queryByText(/処理失敗（0 件）/)).not.toBeInTheDocument();
  });
});

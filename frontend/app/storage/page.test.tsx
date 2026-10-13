import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { StorageOverview } from "@/lib/api/storage";
const { getStorageOverview } = vi.hoisted(() => ({ getStorageOverview: vi.fn() }));
vi.mock("@/lib/api/storage", () => ({ getStorageOverview }));
import StoragePage from "./page";
const empty: StorageOverview = { totals: { programCount: 0, failedProgramCount: 0, droppedFrameCount: 0, unknownDropsProgramCount: 0, registeredFileCount: 0, registeredBytes: 0, originalTsBytes: 0, encodedBytes: 0, otherBytes: 0, movedFileBytes: 0, pendingFileBytes: 0, verifiedPhysicalBytes: null }, largestRecordings: [], duplicateCandidates: [], estimatedReclaimableBytes: 0, excludedSharedPathCount: 0, verifiedReclaimableBytes: null };
describe("StoragePage", () => {
  it("distinguishes an empty library from unknown physical storage", async () => {
    getStorageOverview.mockResolvedValue(empty);
    render(await StoragePage());
    expect(screen.getByText("録画はまだありません。")).toBeInTheDocument();
    expect(screen.getByText("重複の整理候補はありません。")).toBeInTheDocument();
    expect(screen.getAllByText(/不明（未検証）/)).toHaveLength(2);
    expect(screen.getByRole("link", { name: "録画一覧へ戻る" })).toHaveAttribute("href", "/");
  });
  it("links largest and duplicate candidates, showing retained and pending definitions", async () => {
    const kept = { id: 1, name: "保持番組", registeredBytes: 3000000000 };
    const other = { id: 2, name: "整理番組", registeredBytes: 100 };
    getStorageOverview.mockResolvedValue({ ...empty, largestRecordings: [kept, other], duplicateCandidates: [{ reason: "OVERLAP", retained: kept, removable: [other] }], estimatedReclaimableBytes: 50, excludedSharedPathCount: 1 });
    render(await StoragePage());
    expect(screen.getAllByRole("link", { name: "保持番組" })[0]).toHaveAttribute("href", "/programs/1");
    expect(screen.getAllByRole("link", { name: "整理番組" })[1]).toHaveAttribute("href", "/programs/2");
    expect(screen.getByText("3,000,000,000 bytes")).toBeInTheDocument();
    expect(screen.getByText("50 bytes")).toBeInTheDocument();
    expect(screen.getByText(/保持する番組と共有する 1 パス/)).toBeInTheDocument();
    expect(screen.getByText(/転送完了前の登録も含み/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /削除/ })).not.toBeInTheDocument();
  });
  it("shows unavailable without leaking server error contents or fake zero totals", async () => {
    getStorageOverview.mockRejectedValue(new Error("secret"));
    render(await StoragePage());
    expect(screen.getByRole("alert")).toHaveTextContent("取得できませんでした");
    expect(screen.queryByText(/secret|0 bytes/)).not.toBeInTheDocument();
  });
});

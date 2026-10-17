import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DuplicateReviewGroup } from "@/lib/api/duplicates";
import type { Program } from "@/lib/api/types";
const { previewBulk, applyBulk, refresh } = vi.hoisted(() => ({
  previewBulk: vi.fn(),
  applyBulk: vi.fn(),
  refresh: vi.fn(),
}));
vi.mock("@/lib/api/bulk", () => ({ previewBulk, applyBulk }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
import { DuplicateReviewPanel } from "./duplicate-review-panel";
const programs: Program[] = [1, 2].map((id) => ({
  id,
  name: `番組${id}`,
  executedFileId: id,
  status: "COMPLETED",
  drops: id === 1 ? 0 : -1,
  channel: "NHK",
  channelName: "NHK総合",
  recordedAt: "2026-01-01T10:00:00",
  duration: 3600,
  size: 9000000000,
  title: `番組${id}`,
}));
const group: DuplicateReviewGroup = {
  reason: "SAME_BROADCAST",
  recommendedId: 1,
  recommendation: {
    orderedCriteria: ["DROPS", "STATUS", "DURATION", "SIZE", "ID"],
    decisiveCriterion: "DROPS",
    comparedWithProgramId: 2,
    drops: 0,
    status: "COMPLETED",
    durationSeconds: 3600,
    recordedBytes: 9000000000,
    id: 1,
  },
  members: programs.map((program) => ({
    program,
    availableFiles:
      program.id === 1
        ? [
            {
              id: 1,
              splittedFileId: 1,
              file: "video.mp4",
              size: 500,
              mime: "video/mp4",
              encoding: null,
              status: "FILE_MOVED",
              mp4: true,
              ts: false,
            },
          ]
        : [],
  })),
};
const plan = {
  request: { ids: [2], action: "DELETE" },
  targets: [
    {
      id: 2,
      fingerprint: "hash",
      eligible: true,
      reason: null,
      estimatedMetadataBytes: 500,
      destinations: {},
      snapshot: {
        program: programs[1],
        executed: { file: "original.ts" },
        createdFiles: [{ id: 2, file: "nas/2.mp4", size: 500 }],
        splitFiles: [],
      },
    },
  ],
  originalSourcePreserved: true,
};
beforeEach(() => {
  vi.clearAllMocks();
  previewBulk.mockResolvedValue(plan);
  applyBulk.mockResolvedValue({ outcomes: [{ id: 2, status: "SUCCESS", message: "done" }] });
});
describe("DuplicateReviewPanel", () => {
  it("compares metadata and available files with no implicit selection or deletion", () => {
    render(<DuplicateReviewPanel groups={[group]} />);
    expect(screen.getByText(/保持を推薦・現在は削除対象外/)).toBeInTheDocument();
    expect(screen.getByLabelText("削除対象に選択 #1")).not.toBeChecked();
    expect(screen.getByLabelText("削除対象に選択 #2")).not.toBeChecked();
    expect(screen.getByText("不明")).toBeInTheDocument();
    expect(screen.getByText(/video.mp4/)).toBeInTheDocument();
    expect(screen.getByText("転送完了ファイルなし")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "番組1" })).toHaveAttribute("href", "/programs/1");
    expect(screen.queryByLabelText("操作")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "対象をプレビュー" })).toBeDisabled();
    expect(previewBulk).not.toHaveBeenCalled();
    expect(applyBulk).not.toHaveBeenCalled();
  });
  it("treats probe failure duration zero as unknown in comparison and recommendation", () => {
    const unknown = {
      ...group,
      recommendation: { ...group.recommendation, durationSeconds: 0 },
      members: group.members.map((member) => ({
        ...member,
        program: { ...member.program, duration: 0 },
      })),
    };
    render(<DuplicateReviewPanel groups={[unknown]} />);
    expect(screen.getAllByText("不明")).toHaveLength(3);
    expect(screen.getByText(/推薦値/)).toHaveTextContent("/ 不明 /");
    expect(screen.queryByText("0 秒")).not.toBeInTheDocument();
  });
  it("uses existing preview, original-preservation details, cancel and explicit apply", async () => {
    const user = userEvent.setup();
    render(<DuplicateReviewPanel groups={[group]} />);
    await user.click(screen.getByLabelText("削除対象に選択 #2"));
    await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
    expect(previewBulk).toHaveBeenCalledWith({ ids: [2], action: "DELETE" });
    expect(await screen.findByText("保持する元録画: original.ts")).toBeInTheDocument();
    expect(screen.getByText(/nas\/2.mp4/)).toBeInTheDocument();
    expect(applyBulk).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "キャンセル" }));
    expect(screen.queryByRole("button", { name: "確認して削除を実行" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
    await user.click(await screen.findByRole("button", { name: "確認して削除を実行" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(applyBulk).toHaveBeenCalledWith(plan);
    expect(screen.getByText(/#2: 成功/)).toBeInTheDocument();
  });
  it("allows explicit selection of the recommendation and retains partial/stale failure reports after groups disappear", async () => {
    const user = userEvent.setup();
    applyBulk.mockResolvedValue({
      outcomes: [
        { id: 1, status: "SUCCESS", message: "done" },
        { id: 2, status: "STALE", message: "preview again" },
      ],
    });
    const { rerender } = render(<DuplicateReviewPanel groups={[group]} />);
    await user.click(screen.getByLabelText("削除対象に選択 #1"));
    await user.click(screen.getByLabelText("削除対象に選択 #2"));
    await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
    expect(previewBulk).toHaveBeenCalledWith({ ids: [1, 2], action: "DELETE" });
    await user.click(await screen.findByRole("button", { name: "確認して削除を実行" }));
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByLabelText("削除対象に選択 #1")).not.toBeChecked();
    expect(screen.getByLabelText("削除対象に選択 #2")).toBeChecked();
    rerender(<DuplicateReviewPanel groups={[]} />);
    expect(screen.getByText("重複録画の候補はありません。")).toBeInTheDocument();
    expect(screen.getByText(/#2: 変更あり・再確認が必要/)).toBeInTheDocument();
  });
  it("shows preview failure without triggering deletion", async () => {
    const user = userEvent.setup();
    previewBulk.mockRejectedValue(new Error("secret"));
    render(<DuplicateReviewPanel groups={[group]} />);
    await user.click(screen.getByLabelText("削除対象に選択 #2"));
    await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("プレビューを取得できません");
    expect(applyBulk).not.toHaveBeenCalled();
  });
});

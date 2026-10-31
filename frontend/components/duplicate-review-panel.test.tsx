import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { DuplicateReviewGroup } from "@/lib/api/duplicates";
import type { Program } from "@/lib/api/types";
const { previewBulk, applyBulk, refresh, setDuplicateGroupHidden } = vi.hoisted(() => ({
  previewBulk: vi.fn(),
  applyBulk: vi.fn(),
  refresh: vi.fn(),
  setDuplicateGroupHidden: vi.fn(),
}));
vi.mock("@/lib/api/bulk", () => ({ previewBulk, applyBulk }));
vi.mock("@/lib/api/duplicate-dismissals", () => ({ setDuplicateGroupHidden }));
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
  hidden: false,
};
const hiddenGroup: DuplicateReviewGroup = { ...group, hidden: true };
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
  setDuplicateGroupHidden.mockResolvedValue({ ok: true });
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
  it("warns while every member of a group is selected, since nothing would be kept", async () => {
    const user = userEvent.setup();
    render(<DuplicateReviewPanel groups={[group]} />);
    await user.click(screen.getByLabelText("削除対象に選択 #2"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await user.click(screen.getByLabelText("削除対象に選択 #1"));
    expect(screen.getByRole("alert")).toHaveTextContent("すべて選択しています");
    await user.click(screen.getByLabelText("削除対象に選択 #1"));
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
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
  it("hides a group as an intentional duplicate by its exact members and refreshes", async () => {
    const user = userEvent.setup();
    const reordered = { ...group, members: [...group.members].reverse() };
    render(<DuplicateReviewPanel groups={[reordered]} />);
    await user.click(screen.getByRole("button", { name: "意図した重複として非表示" }));
    expect(setDuplicateGroupHidden).toHaveBeenCalledWith({
      reason: "SAME_BROADCAST",
      programIds: [1, 2],
      hidden: true,
    });
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByText(/#1, #2）を意図した重複として非表示にしました/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(previewBulk).not.toHaveBeenCalled();
    expect(applyBulk).not.toHaveBeenCalled();
  });
  it("shows a stale or failed update without refreshing and allows retrying", async () => {
    const user = userEvent.setup();
    setDuplicateGroupHidden.mockResolvedValueOnce({
      ok: false,
      error: "重複グループの内容が変わりました。再読み込みして確認してください。",
    });
    render(<DuplicateReviewPanel groups={[group]} />);
    const button = screen.getByRole("button", { name: "意図した重複として非表示" });
    await user.click(button);
    expect(await screen.findByRole("alert")).toHaveTextContent("内容が変わりました");
    expect(refresh).not.toHaveBeenCalled();
    expect(button).toBeEnabled();
    setDuplicateGroupHidden.mockRejectedValueOnce(new Error("secret"));
    await user.click(button);
    await waitFor(() => expect(screen.getByRole("alert")).toHaveTextContent("更新できませんでした"));
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
    expect(refresh).not.toHaveBeenCalled();
  });
  it("disables visibility buttons while an update is pending", async () => {
    const user = userEvent.setup();
    let resolve: (value: { ok: boolean }) => void = () => {};
    setDuplicateGroupHidden.mockReturnValueOnce(new Promise((r) => (resolve = r)));
    render(<DuplicateReviewPanel groups={[group]} />);
    const button = screen.getByRole("button", { name: "意図した重複として非表示" });
    await user.click(button);
    expect(button).toBeDisabled();
    expect(screen.getByText("更新中…")).toBeInTheDocument();
    resolve({ ok: true });
    await waitFor(() => expect(button).toBeEnabled());
  });
  it("lists hidden groups apart, without deletion checkboxes, and restores them", async () => {
    const user = userEvent.setup();
    render(<DuplicateReviewPanel groups={[hiddenGroup]} />);
    expect(screen.getByText("重複録画の候補はありません。")).toBeInTheDocument();
    expect(screen.getByText("非表示にしたグループが 1 件あります。")).toBeInTheDocument();
    expect(screen.queryByLabelText("削除対象に選択 #1")).not.toBeInTheDocument();
    expect(screen.getByText("非表示にした重複グループ（1 件）")).toBeInTheDocument();
    expect(screen.getByLabelText("非表示の重複グループ #1, #2")).toHaveTextContent("番組2");
    await user.click(screen.getByRole("button", { name: "再表示する" }));
    expect(setDuplicateGroupHidden).toHaveBeenCalledWith({
      reason: "SAME_BROADCAST",
      programIds: [1, 2],
      hidden: false,
    });
    await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
    expect(screen.getByText(/を再表示しました/)).toBeInTheDocument();
  });
  it("drops a hidden group's members from the deletion selection, also after it is restored", async () => {
    const user = userEvent.setup();
    const other: DuplicateReviewGroup = {
      ...group,
      recommendedId: 3,
      members: [3, 4].map((id) => ({
        program: { ...programs[0], id, executedFileId: id, name: `番組${id}` },
        availableFiles: [],
      })),
    };
    const { rerender } = render(<DuplicateReviewPanel groups={[group, other]} />);
    await user.click(screen.getByLabelText("削除対象に選択 #2"));
    await user.click(screen.getByLabelText("削除対象に選択 #3"));
    expect(screen.getByText("2件選択中")).toBeInTheDocument();
    rerender(<DuplicateReviewPanel groups={[hiddenGroup, other]} />);
    expect(screen.queryByLabelText("削除対象に選択 #2")).not.toBeInTheDocument();
    expect(screen.getByText("1件選択中")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
    expect(previewBulk).toHaveBeenCalledWith({ ids: [3], action: "DELETE" });
    await user.click(await screen.findByRole("button", { name: "キャンセル" }));
    rerender(<DuplicateReviewPanel groups={[group, other]} />);
    expect(screen.getByLabelText("削除対象に選択 #2")).not.toBeChecked();
    expect(screen.getByLabelText("削除対象に選択 #3")).toBeChecked();
  });
});

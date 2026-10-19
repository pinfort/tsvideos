import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { BulkPrograms } from "./bulk-programs";
import type { Program } from "@/lib/api/types";
const { previewBulk, applyBulk, refresh } = vi.hoisted(() => ({
  previewBulk: vi.fn(),
  applyBulk: vi.fn(),
  refresh: vi.fn(),
}));
vi.mock("@/lib/api/bulk", () => ({ previewBulk, applyBulk }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const programs = [1, 2].map(
  (id) =>
    ({
      id,
      name: `録画${id}`,
      executedFileId: id,
      status: "COMPLETED",
      drops: 0,
    }) as Program,
);
const plan = {
  request: { ids: [1, 2], action: "DELETE" },
  targets: programs.map((program) => ({
    id: program.id,
    eligible: true,
    fingerprint: "hash",
    estimatedMetadataBytes: 5,
    destinations: {},
    snapshot: {
      program,
      executed: { file: `source${program.id}` },
      createdFiles: [{ id: program.id, file: `nas/${program.id}`, size: 5 }],
      splitFiles: [],
    },
  })),
};
beforeEach(() => {
  vi.clearAllMocks();
  previewBulk.mockResolvedValue(plan);
  applyBulk.mockResolvedValue({
    outcomes: [
      { id: 1, status: "SUCCESS", message: "done" },
      { id: 2, status: "FAILED", message: "NAS failure" },
    ],
  });
});
it("requires preview and explicit confirmation, permits cancel, then refreshes with failed targets selected", async () => {
  const user = userEvent.setup();
  render(<BulkPrograms programs={programs} />);
  expect(screen.getByText(/選択はこのページ内のみ/)).toBeInTheDocument();
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.click(screen.getByLabelText("録画 2 を選択"));
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(
    await screen.findByText("保持する元録画: source1"),
  ).toBeInTheDocument();
  expect(applyBulk).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "キャンセル" }));
  expect(
    screen.queryByRole("button", { name: "確認して削除を実行" }),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  await user.click(
    await screen.findByRole("button", { name: "確認して削除を実行" }),
  );
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
  expect(screen.getByLabelText("録画 1 を選択")).not.toBeChecked();
  expect(screen.getByLabelText("録画 2 を選択")).toBeChecked();
  expect(screen.getByText(/#2: 失敗/)).toBeInTheDocument();
});
it("sends the directory for move preview and clears selection when the page scope changes", async () => {
  const user = userEvent.setup();
  const { rerender } = render(<BulkPrograms key="page1" programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.selectOptions(screen.getByLabelText("操作"), "MOVE");
  await user.type(screen.getByLabelText("移動先の番組フォルダー名"), "番組");
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(previewBulk).toHaveBeenCalledWith({
    ids: [1],
    action: "MOVE",
    directory: "番組",
  });
  rerender(<BulkPrograms key="page2" programs={programs} />);
  expect(screen.getByLabelText("録画 1 を選択")).not.toBeChecked();
});
it("blocks repeat confirmation while pending and warns when result is unknown", async () => {
  const user = userEvent.setup();
  let reject!: (reason: Error) => void;
  applyBulk.mockReturnValue(
    new Promise((_, r) => {
      reject = r;
    }),
  );
  render(<BulkPrograms programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  await user.click(
    await screen.findByRole("button", { name: "確認して削除を実行" }),
  );
  expect(
    screen.getByRole("button", { name: "確認して削除を実行" }),
  ).toBeDisabled();
  reject(new Error("offline"));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "一部処理済みの可能性",
  );
});
it("counts an id selected twice through renderPrograms once and hides the action picker when deleteOnly", async () => {
  const user = userEvent.setup();
  render(
    <BulkPrograms
      programs={programs}
      deleteOnly
      renderPrograms={(selected, onSelect) => (
        <button type="button" onClick={() => onSelect(1, true)}>
          選択 {selected.join(",")}
        </button>
      )}
    />,
  );
  expect(screen.queryByLabelText("操作")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: /選択/ }));
  await user.click(screen.getByRole("button", { name: /選択/ }));
  expect(screen.getByText("1件選択中")).toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(previewBulk).toHaveBeenCalledWith({ ids: [1], action: "DELETE" });
});

it("starts confirmation on cancel and restores preview focus after keyboard cancellation", async () => {
  const user = userEvent.setup();
  render(<BulkPrograms programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  const preview = screen.getByRole("button", { name: "対象をプレビュー" });
  await user.click(preview);
  expect(await screen.findByRole("alertdialog")).toHaveAccessibleName(
    "削除の確認（2件）",
  );
  expect(screen.getByRole("button", { name: "キャンセル" })).toHaveFocus();
  await user.keyboard("{Escape}");
  await waitFor(() => expect(preview).toHaveFocus());
  expect(applyBulk).not.toHaveBeenCalled();
  expect(screen.getByLabelText("録画 1 を選択")).toBeChecked();
});

it("does not dismiss confirmation with Escape during apply", async () => {
  const user = userEvent.setup();
  let resolve!: (value: { outcomes: [] }) => void;
  applyBulk.mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  render(<BulkPrograms programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  await user.click(
    await screen.findByRole("button", { name: "確認して削除を実行" }),
  );
  await user.keyboard("{Escape}");
  expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "キャンセル" })).toBeDisabled();
  resolve({ outcomes: [] });
  await waitFor(() =>
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument(),
  );
});

it("disables confirmation when no target in the plan is eligible", async () => {
  const user = userEvent.setup();
  previewBulk.mockResolvedValue({
    ...plan,
    targets: plan.targets.map((t) => ({
      ...t,
      eligible: false,
      reason: "未完了",
    })),
  });
  render(<BulkPrograms programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(
    await screen.findByRole("button", { name: "確認して削除を実行" }),
  ).toBeDisabled();
  expect(screen.getAllByText("実行不可: 未完了")).toHaveLength(2);
});

it("words the confirmation for a move and shows each destination", async () => {
  const user = userEvent.setup();
  previewBulk.mockResolvedValue({
    ...plan,
    request: { ids: [1], action: "MOVE", directory: "番組" },
    targets: [{ ...plan.targets[0], destinations: { 1: "番組/1.mp4" } }],
  });
  render(<BulkPrograms programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択"));
  await user.selectOptions(screen.getByLabelText("操作"), "MOVE");
  await user.type(screen.getByLabelText("移動先の番組フォルダー名"), "番組");
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(await screen.findByRole("alertdialog")).toHaveAccessibleName(
    "移動の確認（1件）",
  );
  expect(screen.getByText(/移動対象の登録サイズ合計/)).toBeInTheDocument();
  expect(screen.getByText(/→ 番組\/1\.mp4/)).toBeInTheDocument();
  expect(
    screen.getByRole("button", { name: "確認して移動を実行" }),
  ).toBeEnabled();
});

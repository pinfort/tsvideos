import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { BulkPrograms } from "./bulk-programs";
import type { Program } from "@/lib/api/types";
const { previewBulk, applyBulk, refresh } = vi.hoisted(() => ({ previewBulk: vi.fn(), applyBulk: vi.fn(), refresh: vi.fn() }));
vi.mock("@/lib/api/bulk", () => ({ previewBulk, applyBulk }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const programs = [1, 2].map(id => ({ id, name: `録画${id}`, executedFileId: id, status: "COMPLETED", drops: 0 } as Program));
const plan = { request: { ids: [1, 2], action: "DELETE" }, targets: programs.map(program => ({ id: program.id, eligible: true, fingerprint: "hash", estimatedMetadataBytes: 5, destinations: {}, snapshot: { program, executed: { file: `source${program.id}` }, createdFiles: [{ id: program.id, file: `nas/${program.id}`, size: 5 }], splitFiles: [] } })) };
beforeEach(() => { vi.clearAllMocks(); previewBulk.mockResolvedValue(plan); applyBulk.mockResolvedValue({ outcomes: [{ id: 1, status: "SUCCESS", message: "done" }, { id: 2, status: "FAILED", message: "NAS failure" }] }); });
it("requires preview and explicit confirmation, permits cancel, then refreshes with failed targets selected", async () => {
  const user = userEvent.setup(); render(<BulkPrograms programs={programs} />);
  expect(screen.getByText(/選択はこのページ内のみ/)).toBeInTheDocument();
  await user.click(screen.getByLabelText("録画 1 を選択")); await user.click(screen.getByLabelText("録画 2 を選択"));
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(await screen.findByText("保持する元録画: source1")).toBeInTheDocument();
  expect(applyBulk).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "キャンセル" }));
  expect(screen.queryByRole("button", { name: "確認して削除を実行" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  await user.click(await screen.findByRole("button", { name: "確認して削除を実行" }));
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce());
  expect(screen.getByLabelText("録画 1 を選択")).not.toBeChecked(); expect(screen.getByLabelText("録画 2 を選択")).toBeChecked();
  expect(screen.getByText(/#2: 失敗/)).toBeInTheDocument();
});
it("sends the directory for move preview and clears selection when the page scope changes", async () => {
  const user = userEvent.setup(); const { rerender } = render(<BulkPrograms key="page1" programs={programs} />);
  await user.click(screen.getByLabelText("録画 1 を選択")); await user.selectOptions(screen.getByLabelText("操作"), "MOVE");
  await user.type(screen.getByLabelText("移動先の番組フォルダー名"), "番組"); await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  expect(previewBulk).toHaveBeenCalledWith({ ids: [1], action: "MOVE", directory: "番組" });
  rerender(<BulkPrograms key="page2" programs={programs} />); expect(screen.getByLabelText("録画 1 を選択")).not.toBeChecked();
});
it("blocks repeat confirmation while pending and warns when result is unknown", async () => {
  const user = userEvent.setup(); let reject!: (reason: Error) => void; applyBulk.mockReturnValue(new Promise((_, r) => { reject = r; }));
  render(<BulkPrograms programs={programs} />); await user.click(screen.getByLabelText("録画 1 を選択")); await user.click(screen.getByRole("button", { name: "対象をプレビュー" }));
  await user.click(await screen.findByRole("button", { name: "確認して削除を実行" })); expect(screen.getByRole("button", { name: "確認して削除を実行" })).toBeDisabled();
  reject(new Error("offline")); expect(await screen.findByRole("alert")).toHaveTextContent("一部処理済みの可能性");
});

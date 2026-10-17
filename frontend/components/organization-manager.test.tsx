import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { OrganizationManager } from "./organization-manager";
const { mutateOrganization, refresh } = vi.hoisted(() => ({
  mutateOrganization: vi.fn(),
  refresh: vi.fn(),
}));
vi.mock("@/lib/api/organization", () => ({ mutateOrganization }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const options = {
  collections: [{ id: 1, name: "番組集", programCount: 2 }],
  tags: [],
};
beforeEach(() => {
  vi.clearAllMocks();
  mutateOrganization.mockResolvedValue({ ok: true });
});
it("creates and renames collections and links contents in recording order", async () => {
  const user = userEvent.setup();
  render(<OrganizationManager options={options} />);
  expect(screen.getByRole("link", { name: /番組集.*録画順/ })).toHaveAttribute(
    "href",
    "/?collectionId=1&sort=RECORDED_AT&direction=ASC",
  );
  await user.type(
    screen.getByLabelText("新しいコレクション名"),
    "日本語シリーズ",
  );
  await user.click(screen.getAllByRole("button", { name: "作成" })[0]);
  expect(mutateOrganization).toHaveBeenCalledWith({
    operation: "create",
    kind: "collections",
    name: "日本語シリーズ",
  });
  await user.clear(screen.getByLabelText("番組集 の名前"));
  await user.type(screen.getByLabelText("番組集 の名前"), "新しい番組集");
  await user.click(screen.getByRole("button", { name: "名前を変更" }));
  expect(mutateOrganization).toHaveBeenLastCalledWith({
    operation: "rename",
    kind: "collections",
    id: 1,
    name: "新しい番組集",
  });
});
it("requires explicit confirmation for metadata removal and permits cancellation", async () => {
  const user = userEvent.setup();
  render(<OrganizationManager options={options} />);
  await user.click(screen.getByRole("button", { name: "番組集 を削除" }));
  expect(
    screen.getByText(/録画や動画ファイルは削除しません/),
  ).toBeInTheDocument();
  expect(mutateOrganization).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "キャンセル" }));
  expect(
    screen.queryByRole("button", { name: "確認して削除" }),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "番組集 を削除" }));
  await user.click(screen.getByRole("button", { name: "確認して削除" }));
  expect(mutateOrganization).toHaveBeenCalledWith({
    operation: "delete",
    kind: "collections",
    id: 1,
  });
  await waitFor(() => expect(refresh).toHaveBeenCalled());
});
it("shows a duplicate error and retains the editable form", async () => {
  mutateOrganization.mockResolvedValue({
    ok: false,
    error: "同じ名前が既に存在します。",
  });
  const user = userEvent.setup();
  render(<OrganizationManager options={options} />);
  await user.type(screen.getByLabelText("新しい手動タグ名"), "tag");
  await user.click(screen.getAllByRole("button", { name: "作成" })[1]);
  expect(await screen.findByRole("alert")).toHaveTextContent("同じ名前");
  expect(refresh).not.toHaveBeenCalled();
});

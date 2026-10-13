import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { ProgramOrganizationEditor } from "./program-organization-editor";
const { mutateOrganization, refresh } = vi.hoisted(() => ({
  mutateOrganization: vi.fn(),
  refresh: vi.fn(),
}));
vi.mock("@/lib/api/organization", () => ({ mutateOrganization }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh }) }));
const collection = { id: 4, name: "シリーズ", programCount: 1 };
const tag = { id: 9, name: "お気に入り", programCount: 1 };
beforeEach(() => {
  vi.clearAllMocks();
  mutateOrganization.mockResolvedValue({ ok: true });
});
it("assigns a collection and creates or reuses a tag by name", async () => {
  const user = userEvent.setup();
  render(
    <ProgramOrganizationEditor
      programId={7}
      membership={{ collections: [], tags: [] }}
      options={{ collections: [collection], tags: [tag] }}
    />,
  );
  await user.selectOptions(screen.getByLabelText("追加するコレクション"), "4");
  await user.click(screen.getAllByRole("button", { name: "関連付ける" })[0]);
  expect(mutateOrganization).toHaveBeenCalledWith({
    operation: "assign",
    kind: "collections",
    id: 4,
    programId: 7,
  });
  await user.type(screen.getByLabelText("タグ名から追加"), "新タグ");
  await user.click(
    screen.getByRole("button", { name: "タグを作成・関連付け" }),
  );
  expect(mutateOrganization).toHaveBeenLastCalledWith({
    operation: "tag",
    programId: 7,
    name: "新タグ",
  });
});
it("unassigns without deleting the label or recording and refreshes", async () => {
  const user = userEvent.setup();
  render(
    <ProgramOrganizationEditor
      programId={7}
      membership={{ collections: [collection], tags: [tag] }}
      options={{ collections: [collection], tags: [tag] }}
    />,
  );
  await user.click(
    screen.getByRole("button", { name: "お気に入り の関連付けを解除" }),
  );
  expect(mutateOrganization).toHaveBeenCalledWith({
    operation: "unassign",
    kind: "tags",
    id: 9,
    programId: 7,
  });
  expect(refresh).toHaveBeenCalled();
  expect(screen.getByRole("link", { name: "シリーズ" })).toHaveAttribute(
    "href",
    "/?collectionId=4&sort=RECORDED_AT&direction=ASC",
  );
});
it("reports disappeared recordings and prevents repeat mutations while pending", async () => {
  let finish!: (r: { ok: boolean; error: string }) => void;
  mutateOrganization.mockReturnValue(
    new Promise((resolve) => {
      finish = resolve;
    }),
  );
  const user = userEvent.setup();
  render(
    <ProgramOrganizationEditor
      programId={7}
      membership={{ collections: [collection], tags: [] }}
      options={{ collections: [collection], tags: [] }}
    />,
  );
  await user.click(
    screen.getByRole("button", { name: "シリーズ の関連付けを解除" }),
  );
  expect(
    screen.getByRole("button", { name: "シリーズ の関連付けを解除" }),
  ).toBeDisabled();
  finish({ ok: false, error: "対象が見つかりません。" });
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "対象が見つかりません",
  );
});

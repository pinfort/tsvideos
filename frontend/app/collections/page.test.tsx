import { render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
const { getOrganizationOptions } = vi.hoisted(() => ({
  getOrganizationOptions: vi.fn(),
}));
vi.mock("@/lib/api/organization", () => ({ getOrganizationOptions }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
import CollectionsPage from "./page";
beforeEach(() => vi.clearAllMocks());
it("shows empty manual organization lists", async () => {
  getOrganizationOptions.mockResolvedValue({ collections: [], tags: [] });
  render(await CollectionsPage());
  expect(screen.getAllByText("まだ登録されていません。")).toHaveLength(2);
});
it("shows a recoverable error instead of broken collection entries", async () => {
  getOrganizationOptions.mockRejectedValue(new Error("offline"));
  render(await CollectionsPage());
  expect(screen.getByRole("alert")).toHaveTextContent("一覧を取得できません");
  expect(screen.getByRole("link", { name: "録画一覧へ" })).toHaveAttribute(
    "href",
    "/",
  );
});

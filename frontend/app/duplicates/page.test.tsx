import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
const { getDuplicateReview } = vi.hoisted(() => ({ getDuplicateReview: vi.fn() }));
vi.mock("@/lib/api/duplicates", () => ({ getDuplicateReview }));
vi.mock("@/components/duplicate-review-panel", () => ({
  DuplicateReviewPanel: ({ groups }: { groups: unknown[] }) => (
    <div>{groups.length ? "comparison" : "empty comparison"}</div>
  ),
}));
import DuplicatesPage from "./page";
describe("DuplicatesPage", () => {
  it("renders server groups without web matching", async () => {
    getDuplicateReview.mockResolvedValue({ groups: [{}] });
    render(await DuplicatesPage());
    expect(screen.getByText("comparison")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "録画一覧へ戻る" })).toHaveAttribute("href", "/");
    // Hidden groups are fetched too, so they can be listed and restored.
    expect(getDuplicateReview).toHaveBeenCalledWith(true);
  });
  it("keeps the comparison panel mounted when groups disappear so operation results survive", async () => {
    getDuplicateReview.mockResolvedValue({ groups: [] });
    render(await DuplicatesPage());
    expect(screen.getByText("empty comparison")).toBeInTheDocument();
  });
  it("shows a safe unavailable state", async () => {
    getDuplicateReview.mockRejectedValue(new Error("secret"));
    render(await DuplicatesPage());
    expect(screen.getByRole("alert")).toHaveTextContent("取得できませんでした");
    expect(screen.queryByText(/secret/)).not.toBeInTheDocument();
  });
});

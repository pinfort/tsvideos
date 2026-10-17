import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
const { getLibraryHealth } = vi.hoisted(() => ({ getLibraryHealth: vi.fn() }));
vi.mock("@/lib/api/health", () => ({ getLibraryHealth }));
vi.mock("@/components/library-health-dashboard", () => ({
  LibraryHealthDashboard: ({ initial }: { initial: unknown }) => (
    <div>{initial === null ? "unavailable" : "saved state"}</div>
  ),
}));
import HealthPage from "./page";
describe("HealthPage", () => {
  it("loads saved state only", async () => {
    getLibraryHealth.mockResolvedValue({});
    render(await HealthPage());
    expect(screen.getByText("saved state")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "録画一覧へ戻る" })).toHaveAttribute("href", "/");
  });
  it("permits retry when initial fetch fails", async () => {
    getLibraryHealth.mockRejectedValue(new Error("secret"));
    render(await HealthPage());
    expect(screen.getByText("unavailable")).toBeInTheDocument();
  });
});

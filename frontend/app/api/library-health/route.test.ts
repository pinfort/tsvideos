import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";

const { getLibraryHealth, verifyLibraryHealth } = vi.hoisted(() => ({
  getLibraryHealth: vi.fn(),
  verifyLibraryHealth: vi.fn(),
}));
vi.mock("@/lib/api/health", () => ({ getLibraryHealth, verifyLibraryHealth }));

import { GET, POST } from "./route";

describe("health proxy", () => {
  it("GET reads without scanning", async () => {
    getLibraryHealth.mockResolvedValue({ latestRun: null });

    const response = await GET();

    expect(await response.json()).toEqual({ latestRun: null });
    expect(verifyLibraryHealth).not.toHaveBeenCalled();
  });

  it("POST explicitly scans", async () => {
    verifyLibraryHealth.mockResolvedValue({ latestRun: { state: "COMPLETED" } });

    const response = await POST();

    expect(response.status).toBe(200);
    expect(verifyLibraryHealth).toHaveBeenCalledOnce();
  });

  it("preserves conflict but hides upstream secrets", async () => {
    verifyLibraryHealth.mockRejectedValue(new ApiError(409, "secret"));

    const response = await POST();

    expect(response.status).toBe(409);
    expect(await response.text()).not.toContain("secret");
  });

  it("reports unavailable separately", async () => {
    getLibraryHealth.mockRejectedValue(new Error("secret"));

    const response = await GET();

    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain("secret");
  });
});

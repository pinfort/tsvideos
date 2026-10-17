import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";

const { getLibraryHealth, verifyLibraryHealth } = vi.hoisted(() => ({
  getLibraryHealth: vi.fn(),
  verifyLibraryHealth: vi.fn(),
}));
vi.mock("@/lib/api/health", () => ({ getLibraryHealth, verifyLibraryHealth }));

import { GET, POST as post } from "./route";

const POST = (headers: Record<string, string> = { host: "tv.local:3000" }) =>
  post(new Request("http://tv.local:3000/api/library-health", { method: "POST", headers }));

beforeEach(() => vi.clearAllMocks());

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

  it("reports a backend error as 502", async () => {
    getLibraryHealth.mockRejectedValue(new ApiError(500, "secret"));

    const response = await GET();

    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain("secret");
  });

  it("reports an unreachable or timed-out backend as 504", async () => {
    getLibraryHealth.mockRejectedValue(new Error("secret"));

    const response = await GET();

    expect(response.status).toBe(504);
    expect(await response.text()).not.toContain("secret");
  });

  it("accepts a same-origin POST, including behind a forwarding proxy", async () => {
    verifyLibraryHealth.mockResolvedValue({ latestRun: null });

    expect((await POST({ host: "tv.local:3000", origin: "http://tv.local:3000" })).status).toBe(
      200,
    );
    expect(
      (
        await POST({
          host: "127.0.0.1:3000",
          "x-forwarded-host": "tv.example",
          origin: "https://tv.example",
        })
      ).status,
    ).toBe(200);
  });

  it("rejects a cross-origin POST without scanning", async () => {
    const response = await POST({ host: "tv.local:3000", origin: "https://evil.example" });

    expect(response.status).toBe(403);
    expect(verifyLibraryHealth).not.toHaveBeenCalled();
  });
});

import { afterEach, describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./client")>()),
  fetchJson,
}));
import { getLibraryHealth, verifyLibraryHealth, refreshLibraryHealth } from "./health";
afterEach(() => vi.unstubAllGlobals());
describe("health API", () => {
  it("read only queries saved results", async () => {
    fetchJson.mockResolvedValue({ summary: null });
    await expect(getLibraryHealth()).resolves.toEqual({ summary: null });
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/health/library");
  });
  it("scan uses an explicit POST", async () => {
    await verifyLibraryHealth();
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/health/library/verify", { method: "POST" });
  });
  it("browser uses same-origin proxy with no caching", async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ summary: null }) });
    vi.stubGlobal("fetch", fetch);
    await refreshLibraryHealth(true);
    expect(fetch).toHaveBeenCalledWith("/api/library-health", {
      method: "POST",
      cache: "no-store",
    });
  });
  it("browser preserves conflict status", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 409 }));
    await expect(refreshLibraryHealth(false)).rejects.toMatchObject({ status: 409 });
  });
});

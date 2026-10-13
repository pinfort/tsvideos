import { describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", () => ({ fetchJson }));
import { getStorageOverview } from "./storage";
describe("storage API", () => {
  it("uses the shared read-only client and preserves unknown values", async () => {
    const data = { totals: { verifiedPhysicalBytes: null } };
    fetchJson.mockResolvedValue(data);
    await expect(getStorageOverview()).resolves.toBe(data);
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/storage");
  });
  it("propagates unavailable responses", async () => {
    fetchJson.mockRejectedValue(new Error("offline"));
    await expect(getStorageOverview()).rejects.toThrow("offline");
  });
});

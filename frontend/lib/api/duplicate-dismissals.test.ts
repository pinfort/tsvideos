import { beforeEach, describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./client")>()),
  fetchJson,
}));
import { ApiError } from "./client";
import { setDuplicateGroupHidden, type DuplicateGroupVisibility } from "./duplicate-dismissals";
beforeEach(() => {
  vi.clearAllMocks();
  fetchJson.mockResolvedValue({});
});
describe("setDuplicateGroupHidden", () => {
  it("posts the exact group to hide or restore", async () => {
    await expect(
      setDuplicateGroupHidden({ reason: "SAME_BROADCAST", programIds: [1, 2], hidden: true }),
    ).resolves.toEqual({ ok: true });
    expect(fetchJson).toHaveBeenLastCalledWith("/api/v1/programs/duplicates/hide", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason: "SAME_BROADCAST", programIds: [1, 2] }),
    });
    await expect(
      setDuplicateGroupHidden({ reason: "OVERLAP", programIds: [3, 4], hidden: false }),
    ).resolves.toEqual({ ok: true });
    expect(fetchJson).toHaveBeenLastCalledWith(
      "/api/v1/programs/duplicates/restore",
      expect.objectContaining({ method: "POST", body: JSON.stringify({ reason: "OVERLAP", programIds: [3, 4] }) }),
    );
  });
  it.each([
    { reason: "OTHER", programIds: [1, 2], hidden: true },
    { reason: "OVERLAP", programIds: [1], hidden: true },
    { reason: "OVERLAP", programIds: [1, 1], hidden: true },
    { reason: "OVERLAP", programIds: [0, 1], hidden: true },
    { reason: "OVERLAP", programIds: [1.5, 2], hidden: true },
    { reason: "OVERLAP", programIds: [1, 2], hidden: "yes" },
  ])("rejects a malformed group without calling the server: %o", async (input) => {
    const result = await setDuplicateGroupHidden(input as never);
    expect(result.ok).toBe(false);
    expect(result.error).toContain("不正");
    expect(fetchJson).not.toHaveBeenCalled();
  });
  it("explains stale, invalid and unavailable responses without leaking details", async () => {
    const input: DuplicateGroupVisibility = { reason: "SAME_BROADCAST", programIds: [1, 2], hidden: true };
    fetchJson.mockRejectedValueOnce(new ApiError(409, "secret"));
    expect((await setDuplicateGroupHidden(input)).error).toContain("内容が変わりました");
    fetchJson.mockRejectedValueOnce(new ApiError(400, "secret"));
    expect((await setDuplicateGroupHidden(input)).error).toContain("不正");
    fetchJson.mockRejectedValueOnce(new Error("secret"));
    const failed = await setDuplicateGroupHidden(input);
    expect(failed).toEqual({ ok: false, error: "更新できませんでした。接続を確認してください。" });
  });
});

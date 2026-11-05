import { describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", () => ({ fetchJson }));
import { duplicateReasonLabels, getDuplicateReview, isDuplicateReason } from "./duplicates";
describe("duplicate reasons", () => {
  it("accepts every server reason, labels each one and rejects anything else", () => {
    expect(["SAME_BROADCAST", "OVERLAP", "REBROADCAST", "OTHER_CHANNEL"].every(isDuplicateReason)).toBe(true);
    expect(duplicateReasonLabels.REBROADCAST).toBe("再放送候補");
    expect(duplicateReasonLabels.OTHER_CHANNEL).toBe("別局の同一話候補");
    expect(Object.keys(duplicateReasonLabels)).toEqual([
      "SAME_BROADCAST",
      "OVERLAP",
      "REBROADCAST",
      "OTHER_CHANNEL",
    ]);
    for (const value of ["OTHER", "rebroadcast", "other_channel", "", null, 1])
      expect(isDuplicateReason(value)).toBe(false);
  });
});
describe("duplicate review API", () => {
  it("reads the server's existing detector results without hidden groups by default", async () => {
    const response = { groups: [] };
    fetchJson.mockResolvedValue(response);
    await expect(getDuplicateReview()).resolves.toBe(response);
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/programs/duplicates");
  });
  it("asks for hidden groups only when requested", async () => {
    fetchJson.mockResolvedValue({ groups: [] });
    await getDuplicateReview(true);
    expect(fetchJson).toHaveBeenLastCalledWith("/api/v1/programs/duplicates?includeHidden=true");
  });
  it("propagates unavailable responses", async () => {
    fetchJson.mockRejectedValue(new Error("offline"));
    await expect(getDuplicateReview()).rejects.toThrow("offline");
  });
});

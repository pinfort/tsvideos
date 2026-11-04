import { describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", () => ({ fetchJson }));
import { getDuplicateReview } from "./duplicates";
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

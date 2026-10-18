import { beforeEach, expect, it, vi } from "vitest";
import { previewBulk, applyBulk } from "./bulk";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", () => ({ fetchJson }));
beforeEach(() => vi.clearAllMocks());
it("posts preview and only applies the exact reviewed request and fingerprints", async () => {
  const request = { ids: [7], action: "DELETE" as const };
  await previewBulk(request);
  expect(fetchJson).toHaveBeenCalledWith("/api/v1/programs/bulk/preview", expect.objectContaining({ method: "POST", body: JSON.stringify(request) }));
  await applyBulk({ request, targets: [{ id: 7, fingerprint: "abc", eligible: true, reason: null, snapshot: null, destinations: {}, estimatedMetadataBytes: 0 }], originalSourcePreserved: true });
  expect(fetchJson).toHaveBeenLastCalledWith("/api/v1/programs/bulk/apply", expect.objectContaining({ body: JSON.stringify({ request, fingerprints: { 7: "abc" } }) }));
});

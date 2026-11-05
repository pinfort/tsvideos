import { beforeEach, describe, expect, it, vi } from "vitest";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", () => ({ fetchJson }));
import { applyDuplicateDeletion, previewDuplicateDeletion, type DuplicateDeletionPlan } from "./duplicate-deletion";

beforeEach(() => {
  vi.clearAllMocks();
  fetchJson.mockResolvedValue({});
});

const target = (id: number, fingerprint: string) => ({
  id,
  fingerprint,
  eligible: true,
  reason: null,
  snapshot: null,
  destinations: {},
  estimatedMetadataBytes: 0,
});

describe("duplicate deletion API", () => {
  it("previews the selected groups with every member", async () => {
    const groups = [{ reason: "REBROADCAST" as const, programIds: [1, 2], deleteIds: [2] }];
    await previewDuplicateDeletion(groups);
    expect(fetchJson).toHaveBeenCalledWith("/api/v1/programs/duplicates/delete/preview", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ groups }),
    });
  });

  it("applies only the reviewed groups with target and kept fingerprints", async () => {
    const plan: DuplicateDeletionPlan = {
      request: { ids: [2], action: "DELETE" },
      groups: [{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [2], keptIds: [1] }],
      targets: [target(2, "t2")],
      keptTargets: [target(1, "k1")],
      originalSourcePreserved: true,
    };
    await applyDuplicateDeletion(plan);
    expect(fetchJson).toHaveBeenLastCalledWith(
      "/api/v1/programs/duplicates/delete/apply",
      expect.objectContaining({
        body: JSON.stringify({
          groups: [{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [2] }],
          fingerprints: { 2: "t2" },
          keptFingerprints: { 1: "k1" },
        }),
      }),
    );
  });

  it.each([
    [[{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [1, 2] }]],
    [[{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [] }]],
    [[{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [3] }]],
    [[{ reason: "OTHER", programIds: [1, 2], deleteIds: [2] }]],
    [[{ reason: "OVERLAP", programIds: [1], deleteIds: [1] }]],
    [[{ reason: "OVERLAP", programIds: [1, 1.5], deleteIds: [1] }]],
    [
      [
        { reason: "OVERLAP", programIds: [1, 2], deleteIds: [2] },
        { reason: "OVERLAP", programIds: [2, 3], deleteIds: [3] },
      ],
    ],
    [[]],
  ])("refuses an all-selected or malformed selection without calling the server: %o", async (groups) => {
    await expect(previewDuplicateDeletion(groups as never)).rejects.toThrow();
    expect(fetchJson).not.toHaveBeenCalled();
  });

  it("refuses to apply a plan whose groups would delete every member", async () => {
    const plan = {
      request: { ids: [1, 2], action: "DELETE" },
      groups: [{ reason: "SAME_BROADCAST", programIds: [1, 2], deleteIds: [1, 2], keptIds: [] }],
      targets: [target(1, "a"), target(2, "b")],
      keptTargets: [],
      originalSourcePreserved: true,
    } as DuplicateDeletionPlan;
    await expect(applyDuplicateDeletion(plan)).rejects.toThrow();
    expect(fetchJson).not.toHaveBeenCalled();
  });
});

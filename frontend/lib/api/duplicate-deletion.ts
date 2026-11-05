"use server";

import { fetchJson } from "./client";
import type { BulkOutcome, BulkPlan, BulkTarget } from "./bulk";
import { isDuplicateReason, type DuplicateReason } from "./duplicates";

/** One reviewed group: all of its members and the ones selected for deletion. */
export type DuplicateDeletionGroup = { reason: DuplicateReason; programIds: number[]; deleteIds: number[] };
/** A bulk DELETE preview plus the groups and the kept recordings the server re-checks before each deletion. */
export type DuplicateDeletionPlan = BulkPlan & {
  groups: (DuplicateDeletionGroup & { keptIds: number[] })[];
  keptTargets: BulkTarget[];
};

const validId = (id: unknown) => Number.isSafeInteger(id) && (id as number) > 0;

/** Mirrors the server's checks so a malformed selection never reaches it; the server enforces them regardless. */
function valid(groups: DuplicateDeletionGroup[]) {
  if (!Array.isArray(groups) || groups.length === 0) return false;
  const all = groups.flatMap((g) => (Array.isArray(g?.programIds) ? g.programIds : []));
  return (
    groups.every(
      (g) =>
        typeof g === "object" &&
        g !== null &&
        isDuplicateReason(g.reason) &&
        Array.isArray(g.programIds) &&
        Array.isArray(g.deleteIds) &&
        g.programIds.length >= 2 &&
        g.programIds.every(validId) &&
        new Set(g.programIds).size === g.programIds.length &&
        g.deleteIds.length > 0 &&
        new Set(g.deleteIds).size === g.deleteIds.length &&
        g.deleteIds.every((id) => g.programIds.includes(id)) &&
        // At least one member of every group stays.
        g.deleteIds.length < g.programIds.length,
    ) && new Set(all).size === all.length
  );
}

const post = <T>(path: string, body: unknown) =>
  fetchJson<T>(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

export async function previewDuplicateDeletion(groups: DuplicateDeletionGroup[]): Promise<DuplicateDeletionPlan> {
  if (!valid(groups)) throw new Error("Invalid duplicate deletion selection");
  return post("/api/v1/programs/duplicates/delete/preview", { groups });
}

/** Applies exactly the reviewed groups with the target and kept fingerprints from the preview. */
export async function applyDuplicateDeletion(plan: DuplicateDeletionPlan): Promise<{ outcomes: BulkOutcome[] }> {
  const groups = plan.groups.map(({ reason, programIds, deleteIds }) => ({ reason, programIds, deleteIds }));
  if (!valid(groups)) throw new Error("Invalid duplicate deletion selection");
  return post("/api/v1/programs/duplicates/delete/apply", {
    groups,
    fingerprints: Object.fromEntries(plan.targets.map((t) => [t.id, t.fingerprint])),
    keptFingerprints: Object.fromEntries(plan.keptTargets.map((t) => [t.id, t.fingerprint])),
  });
}

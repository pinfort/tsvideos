"use server";

import { fetchJson } from "./client";
import type { Program } from "./types";
export type BulkRequest = { ids: number[]; action: "DELETE" | "MOVE"; directory?: string };
export type BulkTarget = {
  id: number; fingerprint: string; eligible: boolean; reason: string | null;
  estimatedMetadataBytes: number; destinations: Record<string, string>;
  snapshot: { program: Program; executed: { file: string } | null;
    splitFiles: { id: number; file: string }[];
    createdFiles: { id: number; file: string; size: number }[] } | null;
};
export type BulkPlan = { request: BulkRequest; targets: BulkTarget[]; originalSourcePreserved: boolean };
export type BulkOutcome = { id: number; status: "SUCCESS" | "FAILED" | "STALE" | "NOT_FOUND"; message: string };
export async function previewBulk(request: BulkRequest): Promise<BulkPlan> {
  return fetchJson("/api/v1/programs/bulk/preview", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(request) });
}
export async function applyBulk(plan: BulkPlan): Promise<{ outcomes: BulkOutcome[] }> {
  return fetchJson("/api/v1/programs/bulk/apply", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ request: plan.request, fingerprints: Object.fromEntries(plan.targets.map(t => [t.id, t.fingerprint])) }) });
}

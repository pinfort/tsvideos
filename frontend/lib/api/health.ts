import { ApiError, fetchJson } from "./client";
export type VerificationStatus = "VERIFIED" | "MISSING" | "SIZE_MISMATCH" | "INACCESSIBLE" | "UNCHECKED";
export interface HealthProgram { id: number; name: string; status: "REGISTERED" | "COMPLETED" | "ERROR"; drops: number | null }
export interface HealthRun { id: number; state: "RUNNING" | "COMPLETED" | "FAILED"; startedAt: string; completedAt: string | null; failureCode: string | null }
export interface HealthFinding { programName: string | null; programRemoved: boolean; finding: { programId: number; fileId: number | null; file: string | null; status: VerificationStatus; checkedAt: string; expectedBytes: number | null; actualBytes: number | null; reason: string | null } }
export interface LibraryHealth { failedPrograms: HealthProgram[]; droppedPrograms: HealthProgram[]; latestRun: HealthRun | null; lastCompletedRun: HealthRun | null; findings: HealthFinding[]; summary: Record<VerificationStatus, number> | null }
export const getLibraryHealth = () => fetchJson<LibraryHealth>("/api/v1/health/library");
export const verifyLibraryHealth = () => fetchJson<LibraryHealth>("/api/v1/health/library/verify", { method: "POST" });

/** Same-origin browser endpoint; backend requests still use fetchJson on the server. */
export async function refreshLibraryHealth(scan: boolean): Promise<LibraryHealth> {
  const response = await fetch("/api/library-health", { method: scan ? "POST" : "GET", cache: "no-store" });
  if (!response.ok) throw new ApiError(response.status, "Unable to access library health");
  return response.json() as Promise<LibraryHealth>;
}

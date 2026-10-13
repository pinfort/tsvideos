import { fetchJson } from "./client";
import type { CreatedFile, Program } from "./types";
export type KeepCriterion = "DROPS" | "STATUS" | "DURATION" | "SIZE" | "ID";
export interface DuplicateReviewGroup {
  reason: "SAME_BROADCAST" | "OVERLAP";
  recommendedId: number;
  recommendation: { orderedCriteria: KeepCriterion[]; decisiveCriterion: KeepCriterion; comparedWithProgramId: number; drops: number | null; status: Program["status"]; durationSeconds: number; recordedBytes: number; id: number };
  members: { program: Program; availableFiles: CreatedFile[] }[];
}
export interface DuplicateReview { groups: DuplicateReviewGroup[] }
export const getDuplicateReview = () => fetchJson<DuplicateReview>("/api/v1/programs/duplicates");

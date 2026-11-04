import { fetchJson } from "./client";
import type { CreatedFile, Program } from "./types";
export type KeepCriterion = "DROPS" | "STATUS" | "DURATION" | "SIZE" | "ID";
export type DuplicateReason = "SAME_BROADCAST" | "OVERLAP";
export interface DuplicateReviewGroup {
  reason: DuplicateReason;
  recommendedId: number;
  recommendation: {
    orderedCriteria: KeepCriterion[];
    decisiveCriterion: KeepCriterion;
    comparedWithProgramId: number;
    drops: number | null;
    status: Program["status"];
    durationSeconds: number;
    recordedBytes: number;
    id: number;
  };
  members: { program: Program; availableFiles: CreatedFile[] }[];
  /** An operator marked this exact group (reason and members) as an intentional duplicate. */
  hidden: boolean;
}
export interface DuplicateReview {
  groups: DuplicateReviewGroup[];
}
/** The server leaves hidden groups out unless `includeHidden` is set. */
export const getDuplicateReview = (includeHidden = false) =>
  fetchJson<DuplicateReview>(
    includeHidden ? "/api/v1/programs/duplicates?includeHidden=true" : "/api/v1/programs/duplicates",
  );

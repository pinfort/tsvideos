import { fetchJson } from "./client";
import type { CreatedFile, Program } from "./types";
export type KeepCriterion = "DROPS" | "STATUS" | "DURATION" | "SIZE" | "ID";
export const duplicateReasons = ["SAME_BROADCAST", "OVERLAP", "REBROADCAST", "OTHER_CHANNEL"] as const;
export type DuplicateReason = (typeof duplicateReasons)[number];
/**
 * REBROADCAST (same channel) and OTHER_CHANNEL (different channels, one recording each) match title, single episode
 * and duration only; content identity is not verified.
 */
export const duplicateReasonLabels: Record<DuplicateReason, string> = {
  SAME_BROADCAST: "同じ放送",
  OVERLAP: "録画時間の重複",
  REBROADCAST: "再放送候補",
  OTHER_CHANNEL: "別局の同一話候補",
};
export function isDuplicateReason(value: unknown): value is DuplicateReason {
  return typeof value === "string" && (duplicateReasons as readonly string[]).includes(value);
}
export interface DuplicateReviewGroup {
  reason: DuplicateReason;
  /** The normalized title a REBROADCAST or OTHER_CHANNEL group was matched on (episode as `#N`); null otherwise. */
  comparisonTitle?: string | null;
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

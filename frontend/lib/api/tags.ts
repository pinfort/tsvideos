import type { ProgramDetail } from "./types";

// Mirrors ExecutedFileTag / ExecutedFileCheck in the backend's core module.
export const KNOWN_TAGS = [
  { tag: "ews", label: "緊急警報放送", checker: "emergency_broadcast" },
  { tag: "superimpose", label: "文字スーパー", checker: "emergency_broadcast" },
] as const;

export type KnownTag = (typeof KNOWN_TAGS)[number];

export function detectionToJapanese(
  program: Pick<ProgramDetail, "tags" | "checks">,
  knownTag: KnownTag,
): string {
  if (program.tags.includes(knownTag.tag)) return "あり";
  if (program.checks.includes(knownTag.checker)) return "なし";
  return "未検査";
}

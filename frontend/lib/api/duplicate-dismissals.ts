"use server";

import { ApiError, fetchJson } from "./client";
import type { DuplicateReason } from "./duplicates";

export type DuplicateGroupVisibility = {
  reason: DuplicateReason;
  programIds: number[];
  hidden: boolean;
};

/**
 * Hides a reviewed duplicate group as intentional, or shows it again. Only the review display changes; recordings
 * and files are untouched. The server accepts only the exact group it currently detects.
 */
export async function setDuplicateGroupHidden(
  input: DuplicateGroupVisibility,
): Promise<{ ok: boolean; error?: string }> {
  if (
    (input.reason !== "SAME_BROADCAST" && input.reason !== "OVERLAP") ||
    typeof input.hidden !== "boolean" ||
    !Array.isArray(input.programIds) ||
    input.programIds.length < 2 ||
    !input.programIds.every((id) => Number.isSafeInteger(id) && id > 0) ||
    new Set(input.programIds).size !== input.programIds.length
  )
    return { ok: false, error: "重複グループの指定が不正です。再読み込みしてください。" };
  try {
    await fetchJson(`/api/v1/programs/duplicates/${input.hidden ? "hide" : "restore"}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reason: input.reason, programIds: input.programIds }),
    });
    return { ok: true };
  } catch (error) {
    return {
      ok: false,
      error:
        error instanceof ApiError && error.status === 409
          ? "重複グループの内容が変わりました。再読み込みして確認してください。"
          : error instanceof ApiError && error.status === 400
            ? "重複グループの指定が不正です。再読み込みしてください。"
            : "更新できませんでした。接続を確認してください。",
    };
  }
}

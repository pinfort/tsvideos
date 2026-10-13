"use server";

import { ApiError, fetchJson } from "./client";
import type {
  LibraryLabel,
  Organization,
  OrganizationMutation,
} from "./organization-types";

export async function getOrganizationOptions(): Promise<Organization> {
  const [collections, tags] = await Promise.all([
    fetchJson<LibraryLabel[]>("/api/v1/library/collections"),
    fetchJson<LibraryLabel[]>("/api/v1/library/tags"),
  ]);
  return { collections, tags };
}
export async function getProgramOrganization(
  id: number,
): Promise<Organization> {
  if (!Number.isSafeInteger(id) || id <= 0)
    throw new Error("Invalid program ID");
  return fetchJson(`/api/v1/programs/${id}/organization`);
}
export async function mutateOrganization(
  input: OrganizationMutation,
): Promise<{ ok: boolean; error?: string }> {
  try {
    if (
      !["create", "rename", "delete", "assign", "unassign", "tag"].includes(
        input.operation,
      ) ||
      ("id" in input && (!Number.isSafeInteger(input.id) || input.id <= 0)) ||
      ("programId" in input &&
        (!Number.isSafeInteger(input.programId) || input.programId <= 0))
    )
      return { ok: false, error: "操作またはIDが不正です。" };
    let path: string;
    let method: string;
    if (input.operation === "tag") {
      path = `/api/v1/programs/${input.programId}/tags`;
      method = "POST";
    } else {
      if (input.kind !== "collections" && input.kind !== "tags")
        return { ok: false, error: "種類が不正です。" };
      path = `/api/v1/library/${input.kind}`;
      if (input.operation !== "create") path += `/${input.id}`;
      if (input.operation === "assign" || input.operation === "unassign")
        path += `/programs/${input.programId}`;
      method =
        input.operation === "create"
          ? "POST"
          : input.operation === "delete" || input.operation === "unassign"
            ? "DELETE"
            : "PUT";
    }
    await fetchJson(path, {
      method,
      headers: { "Content-Type": "application/json" },
      ...("name" in input
        ? { body: JSON.stringify({ name: input.name }) }
        : {}),
    });
    return { ok: true };
  } catch (error) {
    return {
      ok: false,
      error:
        error instanceof ApiError && error.status === 409
          ? "同じ名前が既に存在します。"
          : error instanceof ApiError && error.status === 404
            ? "対象が見つかりません。再読み込みしてください。"
            : "更新できませんでした。入力内容と接続を確認してください。",
    };
  }
}

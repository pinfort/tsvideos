"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { mutateOrganization } from "@/lib/api/organization";
import type {
  Organization,
  OrganizationMutation,
} from "@/lib/api/organization-types";

export function ProgramOrganizationEditor({
  programId,
  membership,
  options,
}: {
  programId: number;
  membership: Organization;
  options: Organization;
}) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  async function mutate(input: OrganizationMutation) {
    setPending(true);
    setError("");
    try {
      const result = await mutateOrganization(input);
      if (result.ok) router.refresh();
      else setError(result.error ?? "更新できませんでした。");
    } catch {
      setError("更新結果を確認できません。再読み込みしてください。");
    } finally {
      setPending(false);
    }
  }
  return (
    <section aria-label="コレクションと手動タグ" className="space-y-4">
      <h2 className="text-xl">コレクションと手動タグ</h2>
      <Link href="/collections" className="text-blue-600 underline">
        コレクション・手動タグを管理
      </Link>
      {error && <p role="alert">{error}</p>}
      {pending && <p role="status">更新中…</p>}
      {(["collections", "tags"] as const).map((kind) => (
        <div key={kind}>
          <h3>{kind === "collections" ? "所属コレクション" : "手動タグ"}</h3>
          {!membership[kind].length && <p>未設定</p>}
          <ul>
            {membership[kind].map((label) => (
              <li key={label.id} className="my-2">
                <Link
                  href={`/?${kind === "collections" ? "collectionId" : "libraryTagId"}=${label.id}&sort=RECORDED_AT&direction=ASC`}
                  className="text-blue-600 underline"
                >
                  {label.name}
                </Link>
                <button
                  type="button"
                  className="ml-3 rounded border p-2"
                  disabled={pending}
                  onClick={() =>
                    mutate({
                      operation: "unassign",
                      kind,
                      id: label.id,
                      programId,
                    })
                  }
                >
                  {label.name} の関連付けを解除
                </button>
              </li>
            ))}
          </ul>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const id = Number(new FormData(e.currentTarget).get("id"));
              void mutate({ operation: "assign", kind, id, programId });
            }}
          >
            <label>
              追加する{kind === "collections" ? "コレクション" : "手動タグ"}{" "}
              <select
                name="id"
                required
                disabled={pending}
                defaultValue=""
                className="rounded border p-2"
              >
                <option value="">選択してください</option>
                {options[kind]
                  .filter(
                    (label) => !membership[kind].some((m) => m.id === label.id),
                  )
                  .map((label) => (
                    <option key={label.id} value={label.id}>
                      {label.name}
                    </option>
                  ))}
              </select>
            </label>
            <button disabled={pending} className="ml-2 rounded border p-2">
              関連付ける
            </button>
          </form>
        </div>
      ))}
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const name = String(new FormData(e.currentTarget).get("name") ?? "");
          void mutate({ operation: "tag", programId, name });
        }}
      >
        <label>
          手動タグ名から追加{" "}
          <input
            name="name"
            maxLength={100}
            required
            disabled={pending}
            className="rounded border p-2"
          />
        </label>
        <button disabled={pending} className="ml-2 rounded border p-2">
          手動タグを作成・関連付け
        </button>
        <p className="text-sm">
          同じ名前の手動タグがある場合は再利用します。自動検出タグとは別に保存します。
        </p>
      </form>
    </section>
  );
}

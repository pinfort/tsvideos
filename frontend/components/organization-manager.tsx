"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter } from "next/navigation";
import { mutateOrganization } from "@/lib/api/organization";
import type {
  LibraryKind,
  LibraryLabel,
  Organization,
  OrganizationMutation,
} from "@/lib/api/organization-types";

export function OrganizationManager({ options }: { options: Organization }) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  const [confirmation, setConfirmation] = useState<{
    kind: LibraryKind;
    label: LibraryLabel;
  } | null>(null);
  async function mutate(input: OrganizationMutation) {
    setPending(true);
    setMessage("");
    try {
      const result = await mutateOrganization(input);
      if (!result.ok) setMessage(result.error ?? "更新できませんでした。");
      else {
        setConfirmation(null);
        router.refresh();
      }
    } catch {
      setMessage("更新結果を確認できません。再読み込みしてください。");
    } finally {
      setPending(false);
    }
  }
  return (
    <section className="space-y-6" aria-label="手動の整理">
      <p>
        名前は前後の空白を除き、Unicode
        NFCで統一します。大文字・小文字だけが違う名前は同じものとして扱います。
      </p>
      {message && <p role="alert">{message}</p>}
      {pending && <p role="status">更新中…</p>}
      {(["collections", "tags"] as const).map((kind) => (
        <section key={kind} className="space-y-3">
          <h2 className="text-xl">
            {kind === "collections" ? "コレクション" : "手動タグ"}
          </h2>
          <form
            onSubmit={(e) => {
              e.preventDefault();
              const form = e.currentTarget;
              const name = String(new FormData(form).get("name") ?? "");
              void mutate({ operation: "create", kind, name });
            }}
          >
            <label>
              新しい{kind === "collections" ? "コレクション" : "タグ"}名{" "}
              <input
                name="name"
                required
                maxLength={100}
                disabled={pending}
                className="rounded border p-2"
              />
            </label>
            <button disabled={pending} className="ml-2 rounded border p-2">
              作成
            </button>
          </form>
          {!options[kind].length && <p>まだ登録されていません。</p>}
          <ul className="space-y-3">
            {options[kind].map((label) => (
              <li
                key={`${label.id}:${label.name}`}
                className="rounded border p-3"
              >
                <Link
                  href={`/?${kind === "collections" ? "collectionId" : "tagId"}=${label.id}&sort=RECORDED_AT&direction=ASC`}
                  className="text-blue-600 underline"
                >
                  {label.name}（{label.programCount}件）を録画順に表示
                </Link>
                <form
                  className="mt-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const name = String(
                      new FormData(e.currentTarget).get("name") ?? "",
                    );
                    void mutate({
                      operation: "rename",
                      kind,
                      id: label.id,
                      name,
                    });
                  }}
                >
                  <label>
                    {label.name} の名前{" "}
                    <input
                      name="name"
                      defaultValue={label.name}
                      required
                      maxLength={100}
                      disabled={pending}
                      className="rounded border p-2"
                    />
                  </label>
                  <button
                    disabled={pending}
                    className="ml-2 rounded border p-2"
                  >
                    名前を変更
                  </button>
                  <button
                    type="button"
                    disabled={pending}
                    onClick={() => setConfirmation({ kind, label })}
                    className="ml-2 rounded border p-2"
                  >
                    {label.name} を削除
                  </button>
                </form>
              </li>
            ))}
          </ul>
        </section>
      ))}
      {confirmation && (
        <section
          aria-label="削除の確認"
          className="rounded border border-red-400 p-4"
        >
          <p>
            「{confirmation.label.name}
            」とその関連付けを削除します。録画や動画ファイルは削除しません。
          </p>
          <button
            type="button"
            disabled={pending}
            onClick={() => setConfirmation(null)}
            className="mr-3 rounded border p-2"
          >
            キャンセル
          </button>
          <button
            type="button"
            disabled={pending}
            onClick={() =>
              mutate({
                operation: "delete",
                kind: confirmation.kind,
                id: confirmation.label.id,
              })
            }
            className="rounded border p-2"
          >
            確認して削除
          </button>
        </section>
      )}
    </section>
  );
}

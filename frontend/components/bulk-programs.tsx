"use client";

import { useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { ProgramsTable } from "./programs-table";
import { applyBulk, previewBulk, type BulkPlan, type BulkOutcome } from "@/lib/api/bulk";
import type { Program } from "@/lib/api/types";

const MAX_SELECTION = 100;

const outcomeLabels = {
  SUCCESS: "成功",
  FAILED: "失敗",
  STALE: "変更あり・再確認が必要",
  NOT_FOUND: "対象なし",
};

/**
 * Shared preview → confirm → apply flow for bulk actions.
 *
 * `renderPrograms` replaces the default table with a caller-owned selection UI; it receives the
 * current in-page selection and must call `onSelect` to change it. `deleteOnly` hides the action
 * picker so only DELETE can be previewed.
 */
export function BulkPrograms({
  programs,
  renderPrograms,
  deleteOnly = false,
}: {
  programs: Program[];
  renderPrograms?: (
    selected: number[],
    onSelect: (id: number, checked: boolean) => void,
  ) => ReactNode;
  deleteOnly?: boolean;
}) {
  const router = useRouter();
  const [selected, setSelected] = useState<number[]>([]);
  const [action, setAction] = useState<"DELETE" | "MOVE">("DELETE");
  const [directory, setDirectory] = useState("");
  const [plan, setPlan] = useState<BulkPlan | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [outcomes, setOutcomes] = useState<BulkOutcome[]>([]);
  const currentSelected = selected.filter((id) => programs.some((p) => p.id === id));

  async function preview() {
    setPending(true);
    setError("");
    setOutcomes([]);
    try {
      setPlan(
        await previewBulk({
          ids: currentSelected,
          action,
          ...(action === "MOVE" ? { directory } : {}),
        }),
      );
    } catch (cause) {
      console.error("Bulk preview failed", cause);
      setError("プレビューを取得できません。入力内容と接続を確認してください。");
    } finally {
      setPending(false);
    }
  }

  async function apply() {
    if (!plan) return;
    setPending(true);
    setError("");
    try {
      const result = await applyBulk(plan);
      setOutcomes(result.outcomes);
      // Keep failed targets that are still on this page selected so they can be previewed again.
      setSelected(
        result.outcomes
          .filter((o) => o.status !== "SUCCESS" && programs.some((p) => p.id === o.id))
          .map((o) => o.id),
      );
    } catch (cause) {
      console.error("Bulk apply failed", cause);
      setError(
        "実行結果を取得できません。一部処理済みの可能性があります。再プレビューで状態を確認してください。",
      );
    } finally {
      setPlan(null);
      router.refresh();
      setPending(false);
    }
  }

  function select(id: number, checked: boolean) {
    setSelected((values) =>
      checked
        ? [...new Set([...values, id])].slice(0, MAX_SELECTION)
        : values.filter((v) => v !== id),
    );
  }

  return (
    <section aria-label="録画の一括操作" className="space-y-4">
      <p>
        選択はこのページ内のみ（最大{MAX_SELECTION}件）。検索条件・ページを変更すると解除されます。完了済みの録画のみ操作できます。
      </p>
      <fieldset disabled={pending || plan !== null} className="space-y-3">
        {renderPrograms ? (
          renderPrograms(currentSelected, select)
        ) : (
          <ProgramsTable programs={programs} selected={currentSelected} onSelect={select} />
        )}
        <p>{currentSelected.length}件選択中</p>
        {!deleteOnly && (
          <label>
            操作{" "}
            <select
              value={action}
              onChange={(e) => setAction(e.target.value as "DELETE" | "MOVE")}
              className="border p-2"
            >
              <option value="DELETE">削除</option>
              <option value="MOVE">移動</option>
            </select>
          </label>
        )}
        {action === "MOVE" && (
          <label className="block">
            移動先の番組フォルダー名{" "}
            <input
              className="border p-2"
              value={directory}
              onChange={(e) => setDirectory(e.target.value)}
            />
          </label>
        )}
        <button
          type="button"
          className="rounded border p-2"
          disabled={!currentSelected.length || (action === "MOVE" && !directory.trim())}
          onClick={preview}
        >
          対象をプレビュー
        </button>
      </fieldset>
      {pending && <p role="status">処理中…</p>}
      {error && <p role="alert">{error}</p>}
      {plan && (
        <section aria-label="実行前の確認" className="space-y-3 rounded border p-4">
          <h2>{plan.request.action === "DELETE" ? "削除" : "移動"}の確認</h2>
          <p>
            元のローカル録画ファイルは削除しません。NASとデータベースの処理は一括で取り消せません。途中失敗時は表示されたパスを確認してください。
          </p>
          <p>
            {plan.request.action === "DELETE" ? "削除予定" : "移動対象"}の登録サイズ合計:{" "}
            {plan.targets.reduce((n, t) => n + t.estimatedMetadataBytes, 0).toLocaleString()} bytes
            （実際の空き容量の保証ではありません）
          </p>
          {plan.targets.map((t) => (
            <div key={t.id} className="border-b py-2">
              <h3>
                #{t.id} {t.snapshot?.program.name}
              </h3>
              <p>{t.eligible ? "実行可能" : `実行不可: ${t.reason}`}</p>
              {t.snapshot && (
                <>
                  <p>保持する元録画: {t.snapshot.executed?.file ?? "メタデータなし"}</p>
                  <ul>
                    {t.snapshot.createdFiles.map((f) => (
                      <li key={f.id} className="break-all">
                        {f.file} ({f.size.toLocaleString()} bytes)
                        {t.destinations[f.id] && <> → {t.destinations[f.id]}</>}
                      </li>
                    ))}
                  </ul>
                  <p>
                    分割ファイル記録: {t.snapshot.splitFiles.map((f) => f.file).join(", ") || "なし"}
                    （ローカルファイルは保持）
                  </p>
                </>
              )}
            </div>
          ))}
          <button
            type="button"
            className="mr-3 rounded border p-2"
            disabled={pending}
            onClick={() => setPlan(null)}
          >
            キャンセル
          </button>
          <button
            type="button"
            className="rounded border border-red-500 p-2"
            disabled={pending || !plan.targets.some((t) => t.eligible)}
            onClick={apply}
          >
            確認して{plan.request.action === "DELETE" ? "削除" : "移動"}を実行
          </button>
        </section>
      )}
      {outcomes.length > 0 && (
        <section aria-label="処理結果">
          <h2>処理結果</h2>
          <ul>
            {outcomes.map((o) => (
              <li key={o.id}>
                #{o.id}: {outcomeLabels[o.status]} — {o.message}
              </li>
            ))}
          </ul>
        </section>
      )}
    </section>
  );
}

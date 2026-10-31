"use client";
import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { BulkPrograms } from "./bulk-programs";
import { Button } from "@/components/ui/button";
import { setDuplicateGroupHidden } from "@/lib/api/duplicate-dismissals";
import type { DuplicateReviewGroup, KeepCriterion } from "@/lib/api/duplicates";
import { programStatusToJapanese } from "@/lib/api/status-labels";

const criteria: Record<KeepCriterion, string> = {
  DROPS: "既知のドロップ数が少ない（不明は後順位）",
  STATUS: "処理状態（完了 → 登録済み → エラー）",
  DURATION: "録画時間が長い",
  SIZE: "録画元のサイズが大きい",
  ID: "ID が小さい",
};
function formatDuration(seconds: number) {
  // A failed duration probe is stored as 0, so non-positive means unknown.
  return seconds <= 0 ? "不明" : `${Math.round(seconds)} 秒`;
}

function reasonLabel(group: DuplicateReviewGroup) {
  return group.reason === "SAME_BROADCAST" ? "同じ放送" : "録画時間が重複";
}
function memberIds(group: DuplicateReviewGroup) {
  return group.members.map(({ program }) => program.id).sort((a, b) => a - b);
}
// Groups never share programs, so reason plus members names a group even when its recommendation changes.
function groupKey(group: DuplicateReviewGroup) {
  return `${group.reason}:${memberIds(group).join(",")}`;
}
function memberLabel(group: DuplicateReviewGroup) {
  return memberIds(group)
    .map((id) => `#${id}`)
    .join(", ");
}

export function DuplicateReviewPanel({ groups }: { groups: DuplicateReviewGroup[] }) {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState("");
  const [error, setError] = useState("");
  const visible = groups.filter((group) => !group.hidden);
  const hidden = groups.filter((group) => group.hidden);
  // Hidden groups are not offered for deletion, and BulkPrograms drops their members from the selection.
  const programs = visible.flatMap((group) => group.members.map((member) => member.program));

  async function setHidden(group: DuplicateReviewGroup, hide: boolean) {
    setPending(true);
    setNotice("");
    setError("");
    try {
      const result = await setDuplicateGroupHidden({
        reason: group.reason,
        programIds: memberIds(group),
        hidden: hide,
      });
      if (result.ok) {
        setNotice(
          hide
            ? `グループ（${memberLabel(group)}）を意図した重複として非表示にしました。`
            : `グループ（${memberLabel(group)}）を再表示しました。`,
        );
        router.refresh();
      } else {
        setError(result.error ?? "更新できませんでした。");
      }
    } catch (cause) {
      console.error("Duplicate group visibility update failed", cause);
      setError("更新できませんでした。接続を確認してください。");
    } finally {
      setPending(false);
    }
  }

  const review = (
    <BulkPrograms
      programs={programs}
      deleteOnly
      renderPrograms={(selected, onSelect) => (
        <div className="space-y-8">
          {visible.length === 0 && <p>重複録画の候補はありません。</p>}
          {visible.length === 0 && hidden.length > 0 && (
            <p>非表示にしたグループが {hidden.length} 件あります。</p>
          )}
          {visible.map((group) => (
            <section
              key={groupKey(group)}
              className="rounded-lg border p-4"
              aria-label={`重複グループ ${group.recommendedId}`}
            >
              <div className="flex flex-wrap items-start justify-between gap-2">
                <h2 className="text-xl font-semibold">{reasonLabel(group)}</h2>
                <Button
                  type="button"
                  variant="outline"
                  disabled={pending}
                  onClick={() => setHidden(group, true)}
                >
                  意図した重複として非表示
                </Button>
              </div>
              <p className="mt-1 text-sm">
                非表示にしても録画とファイルは削除されません。録画が増減してグループの構成が変わると、再びここに表示されます。
              </p>
              <p className="mt-2">
                保持を推薦：#{group.recommendedId}。次候補 #
                {group.recommendation.comparedWithProgramId} との決め手は「
                {criteria[group.recommendation.decisiveCriterion]}」です。
              </p>
              <p className="mt-2 text-sm">
                推薦順序：
                {group.recommendation.orderedCriteria.map((key) => criteria[key]).join(" → ")}
              </p>
              <p className="mt-2 text-sm">
                推薦値：ドロップ {group.recommendation.drops ?? "不明"} /{" "}
                {programStatusToJapanese(group.recommendation.status)} /{" "}
                {formatDuration(group.recommendation.durationSeconds)} /{" "}
                {group.recommendation.recordedBytes.toLocaleString("ja-JP")} bytes / ID{" "}
                {group.recommendation.id}
              </p>
              {group.members.every(({ program }) => selected.includes(program.id)) && (
                <p role="alert" className="mt-2 font-bold text-red-700 dark:text-red-300">
                  このグループの録画をすべて選択しています。実行すると、この放送の録画は 1
                  件も残りません。
                </p>
              )}
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-sm">
                  <caption className="text-left">
                    選択は初期状態では空です。推薦された録画も、明示的に選択すると削除対象になります。
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col" className="p-2">
                        比較項目
                      </th>
                      {group.members.map(({ program }) => (
                        <th key={program.id} scope="col" className="min-w-60 p-2">
                          <label className="flex gap-2">
                            <input
                              type="checkbox"
                              aria-label={`削除対象に選択 #${program.id}`}
                              checked={selected.includes(program.id)}
                              onChange={(e) => onSelect(program.id, e.target.checked)}
                            />
                            削除対象に選択
                          </label>
                          <Link
                            className="mt-2 inline-block underline"
                            href={`/programs/${program.id}`}
                          >
                            {program.name}
                          </Link>
                          {program.id === group.recommendedId && (
                            <p className="font-bold">
                              保持を推薦{!selected.includes(program.id) && "・現在は削除対象外"}
                            </p>
                          )}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        チャンネル
                      </th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.channelName}（{program.channel}）
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        録画日時（録画環境のローカル時刻）
                      </th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.recordedAt}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        録画時間
                      </th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {formatDuration(program.duration)}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        ドロップ数
                      </th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.drops < 0 ? "不明" : program.drops}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        処理状態
                      </th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {programStatusToJapanese(program.status)}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th scope="row" className="p-2">
                        利用可能として登録されたファイル
                      </th>
                      {group.members.map(({ program, availableFiles }) => (
                        <td key={program.id} className="p-2">
                          {availableFiles.length ? (
                            <ul>
                              {availableFiles.map((file) => (
                                <li className="break-all" key={file.id}>
                                  {file.file}（{file.mime ?? "形式不明"} /{" "}
                                  {file.size.toLocaleString("ja-JP")} bytes）
                                </li>
                              ))}
                            </ul>
                          ) : (
                            "転送完了ファイルなし"
                          )}
                        </td>
                      ))}
                    </tr>
                  </tbody>
                </table>
              </div>
            </section>
          ))}
        </div>
      )}
    />
  );
  return (
    <div className="space-y-8">
      {/* Live regions stay mounted so screen readers announce the text change. */}
      <p role="status">{pending ? "更新中…" : notice}</p>
      {error && <p role="alert">{error}</p>}
      {review}
      {hidden.length > 0 && (
        <section aria-label="非表示にした重複グループ" className="rounded-lg border p-4">
          <details>
            <summary className="cursor-pointer font-semibold">
              非表示にした重複グループ（{hidden.length} 件）
            </summary>
            <p className="mt-2 text-sm">
              意図した重複として確認済みのグループです。削除対象には選べません。再表示すると上の一覧に戻ります。
            </p>
            <ul className="mt-2 space-y-3">
              {hidden.map((group) => (
                <li
                  key={groupKey(group)}
                  aria-label={`非表示の重複グループ ${memberLabel(group)}`}
                  className="border-t pt-3"
                >
                  <p className="font-semibold">{reasonLabel(group)}</p>
                  <ul className="text-sm">
                    {group.members.map(({ program }) => (
                      <li key={program.id}>
                        <Link className="underline" href={`/programs/${program.id}`}>
                          {program.name}
                        </Link>{" "}
                        #{program.id} / {program.channelName} / {program.recordedAt}
                      </li>
                    ))}
                  </ul>
                  <Button
                    type="button"
                    variant="outline"
                    className="mt-2"
                    disabled={pending}
                    onClick={() => setHidden(group, false)}
                  >
                    再表示する
                  </Button>
                </li>
              ))}
            </ul>
          </details>
        </section>
      )}
    </div>
  );
}

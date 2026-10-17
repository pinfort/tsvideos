"use client";
import Link from "next/link";
import { BulkPrograms } from "./bulk-programs";
import type { DuplicateReviewGroup, KeepCriterion } from "@/lib/api/duplicates";
import { programStatusToJapanese } from "@/lib/api/status-labels";

const criteria: Record<KeepCriterion, string> = {
  DROPS: "既知のドロップ数が少ない（不明は後順位）",
  STATUS: "処理状態（完了 → 登録済み → エラー）",
  DURATION: "録画時間が長い",
  SIZE: "録画元のサイズが大きい",
  ID: "ID が小さい",
};
export function DuplicateReviewPanel({ groups }: { groups: DuplicateReviewGroup[] }) {
  const programs = groups.flatMap((group) => group.members.map((member) => member.program));
  return (
    <BulkPrograms
      programs={programs}
      deleteOnly
      renderPrograms={(selected, onSelect) => (
        <div className="space-y-8">
          {groups.length === 0 && <p>重複録画の候補はありません。</p>}
          {groups.map((group) => (
            <section
              key={group.recommendedId}
              className="rounded-lg border p-4"
              aria-label={`重複グループ ${group.recommendedId}`}
            >
              <h2 className="text-xl font-semibold">
                {group.reason === "SAME_BROADCAST" ? "同じ放送" : "録画時間が重複"}
              </h2>
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
                {group.recommendation.durationSeconds <= 0
                  ? "不明"
                  : `${group.recommendation.durationSeconds} 秒`}{" "}
                / {group.recommendation.recordedBytes.toLocaleString("ja-JP")} bytes / ID{" "}
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
                      <th className="p-2">比較項目</th>
                      {group.members.map(({ program }) => (
                        <th key={program.id} className="min-w-60 p-2">
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
                      <th className="p-2">チャンネル</th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.channelName}（{program.channel}）
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th className="p-2">録画日時（録画環境のローカル時刻）</th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.recordedAt}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th className="p-2">録画時間</th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.duration <= 0 ? "不明" : `${program.duration} 秒`}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th className="p-2">ドロップ数</th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {program.drops < 0 ? "不明" : program.drops}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th className="p-2">処理状態</th>
                      {group.members.map(({ program }) => (
                        <td key={program.id} className="p-2">
                          {programStatusToJapanese(program.status)}
                        </td>
                      ))}
                    </tr>
                    <tr className="border-t">
                      <th className="p-2">利用可能として登録されたファイル</th>
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
}

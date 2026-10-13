"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { ApiError } from "@/lib/api/client";
import { refreshLibraryHealth, type LibraryHealth, type VerificationStatus, type HealthProgram } from "@/lib/api/health";

const statuses: Record<VerificationStatus, string> = { VERIFIED: "確認済み", MISSING: "ファイル欠落", SIZE_MISMATCH: "サイズ不一致", INACCESSIBLE: "NAS に接続・アクセス不可", UNCHECKED: "未検証" };
const numberOrUnknown = (value: number | null) => value === null ? "不明" : value.toLocaleString("ja-JP");

export function LibraryHealthDashboard({ initial }: { initial: LibraryHealth | null }) {
  const [health, setHealth] = useState(initial);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState(initial === null ? "ヘルス情報を取得できませんでした。状態を再読み込みしてください。" : "");
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => { const update = () => setNow(Date.now()); update(); const timer = setInterval(update, 60000); return () => clearInterval(timer); }, []);

  async function refresh(scan: boolean) {
    setPending(true); setMessage("");
    try { setHealth(await refreshLibraryHealth(scan)); }
    catch (error) { setMessage(error instanceof ApiError && error.status === 409 ? "別の検証が実行中です。状態を再読み込みしてください。" : "リクエストを完了できませんでした。検証が継続している可能性があります。状態を再読み込みしてください。"); }
    finally { setPending(false); }
  }
  const completed = health?.lastCompletedRun;
  const age = completed?.completedAt && now !== null ? Math.max(0, Math.floor((now - Date.parse(completed.completedAt)) / 60000)) : null;
  return <>
    <div className="flex flex-wrap gap-3"><button className="rounded border px-4 py-2 disabled:opacity-50" disabled={pending || health?.latestRun?.state === "RUNNING"} onClick={() => refresh(true)}>NAS の検証を実行</button><button className="rounded border px-4 py-2 disabled:opacity-50" disabled={pending} onClick={() => refresh(false)}>状態を再読み込み</button></div>
    <p className="text-sm">検証は NAS の存在・サイズを読み取ります。録画ファイルは変更しません。ページ表示や状態の再読み込みでは検証しません。</p>
    {pending && <p role="status">処理中です。結果を待っています。</p>}
    {message && <p role="alert">{message}</p>}
    {health && <>
      <section aria-labelledby="attention-heading"><h2 id="attention-heading" className="text-xl font-semibold">対応が必要な録画</h2><ProgramList title="処理失敗" programs={health.failedPrograms} /><ProgramList title="ドロップあり" programs={health.droppedPrograms} /></section>
      <section aria-labelledby="verification-heading"><h2 id="verification-heading" className="text-xl font-semibold">保存ファイルの検証</h2>
        {health.latestRun?.state === "RUNNING" && <p role="status" className="mt-3">検証実行中（開始 UTC: {health.latestRun.startedAt}）。結果は完了後に更新されます。</p>}
        {health.latestRun?.state === "FAILED" && <p role="alert" className="mt-3">最新の検証は失敗しました（UTC: {health.latestRun.completedAt ?? health.latestRun.startedAt}）。前回完了した結果を表示しています。完了履歴がない場合は未検証です。</p>}
        {completed ? <p className="mt-3">最終検証完了 UTC: <time dateTime={completed.completedAt ?? completed.startedAt}>{completed.completedAt ?? completed.startedAt}</time>{age !== null && `（${age.toLocaleString("ja-JP")} 分前）`}</p> : <p className="mt-3">完了した検証はまだありません。ファイルの状態は未検証です。</p>}
        <p className="mt-2 text-sm">保存済みの結果です。検証後のファイル変更は反映されません。「アクセス不可」は欠落の確認ではありません。「未検証」には転送未完了やファイル未登録も含みます。</p>
        <dl className="my-4 grid gap-3 rounded-lg border p-4 sm:grid-cols-2">{(Object.keys(statuses) as VerificationStatus[]).map(status => <div key={status}><dt>{statuses[status]}</dt><dd className="font-mono">{health.summary === null ? "未検証" : `${health.summary[status]} 件`}</dd></div>)}</dl>
        {completed && health.findings.length === 0 && <p>検証時に登録されていたファイル・番組はありません。</p>}
        {health.findings.length > 0 && <div className="overflow-x-auto"><table className="w-full text-left text-sm"><thead><tr><th className="p-2">番組 / ファイル</th><th className="p-2">検証結果</th><th className="p-2">登録 / 実測 bytes</th><th className="p-2">確認 UTC</th></tr></thead><tbody>{health.findings.map((entry, index) => <tr className="border-t" key={`${entry.finding.programId}-${entry.finding.fileId}-${index}`}><td className="p-2">{entry.programRemoved ? <span>削除済み番組 #{entry.finding.programId}</span> : <Link className="underline" href={`/programs/${entry.finding.programId}`}>{entry.programName ?? `番組 #${entry.finding.programId}`}</Link>}<p className="break-all">{entry.finding.file ?? "登録ファイルなし"}</p></td><td className="p-2">{statuses[entry.finding.status]}</td><td className="p-2">{numberOrUnknown(entry.finding.expectedBytes)} / {numberOrUnknown(entry.finding.actualBytes)}</td><td className="p-2"><time dateTime={entry.finding.checkedAt}>{entry.finding.checkedAt}</time></td></tr>)}</tbody></table></div>}
      </section>
    </>}
  </>;
}
function ProgramList({ title, programs }: { title: string; programs: HealthProgram[] }) {
  return <div className="mt-4"><h3 className="font-semibold">{title}（{programs.length} 件）</h3>{programs.length === 0 ? <p className="text-sm">該当する録画はありません。</p> : <ul className="list-inside list-disc">{programs.map(program => <li key={program.id}><Link className="underline" href={`/programs/${program.id}`}>{program.name}</Link>（ドロップ: {program.drops === null || program.drops < 0 ? "不明" : program.drops}）</li>)}</ul>}</div>;
}

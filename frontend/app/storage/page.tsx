import Link from "next/link";
import { getStorageOverview } from "@/lib/api/storage";

const bytes = (value: number | null) => value === null ? "不明（未検証）" : `${value.toLocaleString("ja-JP")} bytes`;

export default async function StoragePage() {
  let overview;
  try { overview = await getStorageOverview(); } catch {
    return <main className="mx-auto w-full max-w-4xl px-6 py-10"><h1 className="text-2xl font-bold">ストレージ概要</h1><p role="alert" className="my-6">ストレージ情報を取得できませんでした。時間をおいて再読み込みしてください。</p><Link href="/">録画一覧へ戻る</Link></main>;
  }
  const { totals } = overview;
  return <main className="mx-auto flex w-full max-w-4xl flex-col gap-8 px-6 py-10">
    <header><Link href="/" className="underline">録画一覧へ戻る</Link><h1 className="mt-4 text-3xl font-bold">ストレージ概要</h1><p className="mt-2">登録されたファイル情報から使用量と整理候補を確認できます。ファイルは削除されません。</p></header>
    <section aria-labelledby="totals-heading"><h2 id="totals-heading" className="text-xl font-semibold">登録メタデータの容量</h2>
      <dl className="mt-4 grid grid-cols-1 gap-4 rounded-lg border p-5 sm:grid-cols-2">
        {[["総容量", totals.registeredBytes], ["オリジナル TS", totals.originalTsBytes], ["エンコード済み動画", totals.encodedBytes], ["その他", totals.otherBytes], ["転送済みとして登録", totals.movedFileBytes], ["転送完了前の登録", totals.pendingFileBytes], ["検証済みの物理使用量", totals.verifiedPhysicalBytes]].map(([label, value]) => <div key={label as string}><dt className="text-sm text-gray-500">{label}</dt><dd className="break-words font-mono">{bytes(value as number | null)}</dd></div>)}
      </dl>
      <p className="mt-3 text-sm">{totals.programCount.toLocaleString("ja-JP")} 番組・{totals.registeredFileCount.toLocaleString("ja-JP")} ファイル。全ステータスの作成ファイルを ID ごとに一度集計しています。NAS の存在・サイズは検証していません。</p>
      <details className="mt-3 text-sm"><summary className="cursor-pointer underline">容量の定義</summary><p className="mt-2">オリジナル TS は MIME が video/vnd.dlna.mpeg-tts で gzip 圧縮されたファイル。エンコード済み動画は TS 以外の video/*。その他には未圧縮 TS、字幕、不明な MIME を含みます。録画元や分割ファイルのサイズは加算しません。転送済みは FILE_MOVED、それ以外は転送完了前です。登録値は実際の物理使用量を保証しません。</p></details>
    </section>
    <section aria-labelledby="largest-heading"><h2 id="largest-heading" className="text-xl font-semibold">容量の大きい録画（上位20件）</h2><p className="mt-2 text-sm">番組ごとの作成ファイル容量順です。ファイルを共有する番組もあるため、この一覧の合計は総容量と一致しない場合があります。</p>
      {overview.largestRecordings.length === 0 ? <p className="mt-4">録画はまだありません。</p> : <div className="mt-4 overflow-x-auto"><table className="w-full text-left"><thead><tr className="border-b"><th className="p-2">番組</th><th className="p-2">登録容量</th></tr></thead><tbody>{overview.largestRecordings.map(recording => <tr key={recording.id} className="border-b"><td className="p-2"><Link className="underline" href={`/programs/${recording.id}`}>{recording.name}</Link></td><td className="whitespace-nowrap p-2 font-mono">{bytes(recording.registeredBytes)}</td></tr>)}</tbody></table></div>}
    </section>
    <section aria-labelledby="duplicates-heading"><h2 id="duplicates-heading" className="text-xl font-semibold">重複の整理候補</h2>
      <p className="mt-3">登録情報に基づく回収候補：<strong>{bytes(overview.estimatedReclaimableBytes)}</strong></p><p>検証済みの回収可能容量：{bytes(overview.verifiedReclaimableBytes)}</p>
      <p className="mt-2 text-sm">すべての整理候補を除去すると仮定した推計です。保持する番組と共有する {overview.excludedSharedPathCount} パスは除外しています。転送完了前の登録も含み、実際に空く容量は未検証です。</p>
      <details className="mt-3 text-sm"><summary className="cursor-pointer underline">候補と推計のルール</summary><p className="mt-2">同一チャンネル・同じ開始時刻または80%以上の時間重複を既存ルールで判定します。ドロップ数、完了状態、長さ、サイズ、ID の順で保持候補を推薦します。パスの区切り・大文字小文字・相対要素を正規化し、全参照番組が整理候補の場合だけ一度集計します。NAS の違いが不明な同一パスは共有とみなし、サイズが矛盾する場合は最小値を使用する保守的な推計です。</p></details>
      {overview.duplicateCandidates.length === 0 ? <p className="mt-4">重複の整理候補はありません。</p> : <ul className="mt-4 space-y-4">{overview.duplicateCandidates.map(group => <li key={group.retained.id} className="rounded-lg border p-4"><p className="text-sm text-gray-500">{group.reason === "SAME_BROADCAST" ? "同じ放送" : "録画時間の重複"}</p><p className="mt-2">保持を推薦：<Link className="font-semibold underline" href={`/programs/${group.retained.id}`}>{group.retained.name}</Link></p><ul className="mt-2 list-inside list-disc">{group.removable.map(recording => <li key={recording.id}>整理候補：<Link className="underline" href={`/programs/${recording.id}`}>{recording.name}</Link>（登録 {bytes(recording.registeredBytes)}）</li>)}</ul></li>)}</ul>}
    </section>
  </main>;
}

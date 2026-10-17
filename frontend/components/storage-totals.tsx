import type { StorageTotals as Totals } from "@/lib/api/storage";
import { formatBytes } from "@/lib/format-bytes";

export function StorageTotals({ totals }: { totals: Totals }) {
  const rows: { label: string; value: number | null }[] = [
    { label: "総容量", value: totals.registeredBytes },
    { label: "オリジナル TS", value: totals.originalTsBytes },
    { label: "エンコード済み動画", value: totals.encodedBytes },
    { label: "その他", value: totals.otherBytes },
    { label: "転送済みとして登録", value: totals.movedFileBytes },
    { label: "転送完了前の登録", value: totals.pendingFileBytes },
    { label: "検証済みの物理使用量", value: totals.verifiedPhysicalBytes },
  ];
  return (
    <>
      <dl className="mt-4 grid grid-cols-1 gap-4 rounded-lg border p-5 sm:grid-cols-2">
        {rows.map(({ label, value }) => (
          <div key={label}>
            <dt className="text-sm text-gray-500">{label}</dt>
            <dd className="break-words font-mono">{formatBytes(value)}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-3 text-sm">
        {totals.programCount.toLocaleString("ja-JP")} 番組・
        {totals.registeredFileCount.toLocaleString("ja-JP")}{" "}
        ファイル。全ステータスの作成ファイルを ID ごとに一度集計しています。NAS
        の存在・サイズは検証していません。
      </p>
      <details className="mt-3 text-sm">
        <summary className="cursor-pointer underline">容量の定義</summary>
        <p className="mt-2">
          オリジナル TS は MIME が video/vnd.dlna.mpeg-tts で gzip 圧縮されたファイル。エンコード済み動画は
          TS 以外の video/*。その他には未圧縮 TS、字幕、不明な MIME を含みます。録画元や分割ファイルのサイズは加算しません。転送済みは
          FILE_MOVED、それ以外は転送完了前です。登録値は実際の物理使用量を保証しません。
        </p>
      </details>
    </>
  );
}

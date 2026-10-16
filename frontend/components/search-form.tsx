import Link from "next/link";
import { MAX_INT, type ProgramQueryParams } from "@/lib/program-query";

export function SearchForm({ values = {} }: { values?: ProgramQueryParams }) {
  const v = (key: string, fallback = "") =>
    typeof values[key] === "string" ? (values[key] as string) : fallback;
  const field =
    "min-w-0 w-full rounded border border-zinc-300 px-3 py-2 text-sm dark:border-zinc-700 dark:bg-zinc-900";
  return (
    <form
      action="/"
      method="get"
      className="flex flex-col gap-4"
      aria-label="録画検索"
    >
      <h1 className="text-2xl font-semibold">録画を検索</h1>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        <label className="flex flex-col gap-1 text-sm">
          番組名
          <input
            name="name"
            defaultValue={v("name")}
            placeholder="番組名で検索"
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          チャンネルID
          <input
            name="channel"
            defaultValue={v("channel")}
            placeholder="例: BS1"
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          処理状態
          <select name="status" defaultValue={v("status")} className={field}>
            <option value="">すべて</option>
            <option value="REGISTERED">登録済み</option>
            <option value="COMPLETED">完了</option>
            <option value="ERROR">エラー</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          録画開始（この日時以降）
          <input
            type="datetime-local"
            step="any"
            name="recordedFrom"
            defaultValue={v("recordedFrom")}
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          録画終了（この日時を含まない）
          <input
            type="datetime-local"
            step="any"
            name="recordedTo"
            defaultValue={v("recordedTo")}
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          利用可能な形式
          <select
            name="fileType"
            defaultValue={v("fileType")}
            className={field}
          >
            <option value="">すべて</option>
            <option value="TS">TS</option>
            <option value="ENCODED">エンコード済み動画</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          ドロップ数（下限）
          <input
            type="number"
            min="0"
            max={MAX_INT}
            step="1"
            name="minDrops"
            defaultValue={v("minDrops")}
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          ドロップ数（上限）
          <input
            type="number"
            min="0"
            max={MAX_INT}
            step="1"
            name="maxDrops"
            defaultValue={v("maxDrops")}
            className={field}
          />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          並び順
          <select
            name="sort"
            defaultValue={v("sort", "RECORDED_AT")}
            className={field}
          >
            <option value="RECORDED_AT">録画日時</option>
            <option value="DURATION">長さ</option>
            <option value="SIZE">サイズ</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          順序
          <select
            name="direction"
            defaultValue={v("direction", "DESC")}
            className={field}
          >
            <option value="DESC">降順</option>
            <option value="ASC">昇順</option>
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          1ページの件数
          <input
            type="number"
            min="1"
            max="500"
            step="1"
            name="limit"
            defaultValue={v("limit", "10")}
            className={field}
            required
          />
        </label>
      </div>
      <input type="hidden" name="offset" value="0" />
      <p className="text-xs text-zinc-500">
        日時は録画環境の現地時刻です。ドロップ数の指定時は未確認の録画を除きます。形式は登録済みファイルの情報で判定します。
      </p>
      <div className="flex items-center gap-4">
        <button
          type="submit"
          className="rounded bg-zinc-900 px-6 py-2 text-sm text-white hover:bg-zinc-700 dark:bg-zinc-100 dark:text-zinc-900"
        >
          検索
        </button>
        <Link href="/" className="text-sm text-blue-600 underline">
          条件をクリア
        </Link>
      </div>
    </form>
  );
}

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NativeSelect } from "@/components/ui/native-select";
import Link from "next/link";
import {
  MAX_INT,
  PROGRAM_QUERY_DEFAULTS,
  type ProgramQueryParams,
} from "@/lib/program-query";

export function SearchForm({
  values = {},
  organization = { collections: [], tags: [] },
}: {
  values?: ProgramQueryParams;
  organization?: import("@/lib/api/organization-types").Organization;
}) {
  const v = (key: string, fallback = "") =>
    typeof values[key] === "string" ? (values[key] as string) : fallback;
  const detailDefaults: Record<string, string> = {
    channel: "",
    collectionId: "",
    libraryTagId: "",
    status: "",
    recordedFrom: "",
    recordedTo: "",
    fileType: "",
    minDrops: "",
    maxDrops: "",
    sort: PROGRAM_QUERY_DEFAULTS.sort,
    direction: PROGRAM_QUERY_DEFAULTS.direction,
    limit: String(PROGRAM_QUERY_DEFAULTS.limit),
  };
  const detailCount = Object.entries(detailDefaults).filter(([key, fallback]) => {
    const value = v(key);
    return value !== "" && value !== fallback;
  }).length;
  return (
    <form
      action="/"
      method="get"
      // Validate on the server so invalid controls inside closed details cannot block submission.
      // Keep numeric bounds for stepper controls and assistive technology.
      noValidate
      className="flex flex-col gap-4"
      aria-label="録画検索"
    >
      <h1 className="text-2xl font-semibold">録画を検索</h1>
      <label className="flex flex-col gap-1 text-sm">
        番組名
        <Input
          name="name"
          defaultValue={v("name")}
          placeholder="番組名で検索"
        />
      </label>
      <details>
        <summary className="cursor-pointer rounded-sm text-sm font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring">
          詳細検索{detailCount > 0 && `（${detailCount}件指定中）`}
        </summary>
        <div className="mt-3 flex flex-col gap-3">
          <Link href="/collections" className="text-link underline">
            コレクション・手動タグを管理
          </Link>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {(["collections", "tags"] as const).map((kind) => {
              const key = kind === "collections" ? "collectionId" : "libraryTagId";
              const current = v(key);
              return (
                <label key={kind} className="flex flex-col gap-1 text-sm">
                  {kind === "collections" ? "コレクション" : "手動タグ"}
                  <NativeSelect name={key} defaultValue={current}>
                    <option value="">すべて</option>
                    {current &&
                      !organization[kind].some(
                        (label) => String(label.id) === current,
                      ) && (
                        <option value={current}>不明・削除済み #{current}</option>
                      )}
                    {organization[kind].map((label) => (
                      <option key={label.id} value={label.id}>
                        {label.name}
                      </option>
                    ))}
                  </NativeSelect>
                </label>
              );
            })}
            <label className="flex flex-col gap-1 text-sm">
              チャンネルID
              <Input
                name="channel"
                defaultValue={v("channel")}
                placeholder="例: BS1"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              処理状態
              <NativeSelect name="status" defaultValue={v("status")}>
                <option value="">すべて</option>
                <option value="REGISTERED">登録済み</option>
                <option value="COMPLETED">完了</option>
                <option value="ERROR">エラー</option>
              </NativeSelect>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              録画開始（この日時以降）
              <Input
                type="datetime-local"
                step="any"
                name="recordedFrom"
                defaultValue={v("recordedFrom")}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              録画終了（この日時を含まない）
              <Input
                type="datetime-local"
                step="any"
                name="recordedTo"
                defaultValue={v("recordedTo")}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              利用可能な形式
              <NativeSelect name="fileType" defaultValue={v("fileType")}>
                <option value="">すべて</option>
                <option value="TS">TS</option>
                <option value="ENCODED">エンコード済み動画</option>
              </NativeSelect>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              ドロップ数（下限）
              <Input
                type="number"
                min="0"
                max={MAX_INT}
                step="1"
                name="minDrops"
                defaultValue={v("minDrops")}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              ドロップ数（上限）
              <Input
                type="number"
                min="0"
                max={MAX_INT}
                step="1"
                name="maxDrops"
                defaultValue={v("maxDrops")}
              />
            </label>
            <label className="flex flex-col gap-1 text-sm">
              並び順
              <NativeSelect
                name="sort"
                defaultValue={v("sort", detailDefaults.sort)}
              >
                <option value="RECORDED_AT">録画日時</option>
                <option value="DURATION">長さ</option>
                <option value="SIZE">サイズ</option>
              </NativeSelect>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              順序
              <NativeSelect
                name="direction"
                defaultValue={v("direction", detailDefaults.direction)}
              >
                <option value="DESC">降順</option>
                <option value="ASC">昇順</option>
              </NativeSelect>
            </label>
            <label className="flex flex-col gap-1 text-sm">
              1ページの件数
              <Input
                type="number"
                min="1"
                max="500"
                step="1"
                name="limit"
                defaultValue={v("limit", detailDefaults.limit)}
              />
            </label>
          </div>
          <p className="text-xs text-muted-foreground">
            日時は録画環境の現地時刻です。ドロップ数の指定時は未確認の録画を除きます。形式は登録済みファイルの情報で判定します。
          </p>
        </div>
      </details>
      <input type="hidden" name="offset" value="0" />
      <div className="flex items-center gap-4">
        <Button type="submit">検索</Button>
        <Link href="/" className="text-sm text-link underline">
          条件をクリア
        </Link>
      </div>
    </form>
  );
}

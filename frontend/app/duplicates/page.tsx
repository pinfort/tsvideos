import Link from "next/link";
import { getDuplicateReview } from "@/lib/api/duplicates";
import { DuplicateReviewPanel } from "@/components/duplicate-review-panel";
export default async function DuplicatesPage() {
  let review;
  try {
    // Hidden groups are listed separately so they can be restored; the panel keeps them out of deletion.
    review = await getDuplicateReview(true);
  } catch {
    return (
      <main className="mx-auto max-w-5xl px-6 py-10">
        <h1 className="text-3xl font-bold">重複録画のレビュー</h1>
        <p role="alert" className="my-6">
          重複情報を取得できませんでした。時間をおいて再読み込みしてください。
        </p>
        <Link href="/">録画一覧へ戻る</Link>
      </main>
    );
  }
  return (
    <main className="mx-auto flex w-full max-w-6xl flex-col gap-6 px-6 py-10">
      <header>
        <Link className="underline" href="/">
          録画一覧へ戻る
        </Link>
        <h1 className="mt-4 text-3xl font-bold">重複録画のレビュー</h1>
        <p className="mt-3">
          CLI
          と同じ判定・推薦順序で表示します。チャンネルが同じで長い方の80%以上が重なる録画（長さ不明時は同じ開始時刻）が候補です。NAS
          は読み取りません。
        </p>
        <p className="mt-2">
          ファイル一覧は FILE_MOVED
          の登録情報です。実際に利用できるかは未検証です。削除する録画を自分で選び、プレビューで対象を確認してください。
        </p>
        <p className="mt-2">
          意図して残している重複は「意図した重複として非表示」でこの一覧から外せます。非表示はこの画面の表示だけに効き、録画・ファイル・CLI
          の整理プランには影響しません。
        </p>
      </header>
      <DuplicateReviewPanel groups={review.groups} />
    </main>
  );
}

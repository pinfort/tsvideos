import Link from "next/link";

export default function ProgramNotFound() {
  return (
    <div className="mx-auto flex w-full max-w-4xl flex-1 flex-col items-center justify-center gap-4 px-6 py-10 text-center">
      <p>指定された番組が見つかりませんでした。</p>
      <Link href="/" className="text-link hover:underline">
        検索に戻る
      </Link>
    </div>
  );
}

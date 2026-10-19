import Link from "next/link";
import { OrganizationManager } from "@/components/organization-manager";
import { getOrganizationOptions } from "@/lib/api/organization";

export default async function CollectionsPage() {
  let options;
  try {
    options = await getOrganizationOptions();
  } catch {
    return (
      <main className="mx-auto max-w-5xl p-8">
        <h1>コレクション・手動タグ</h1>
        <p role="alert">
          一覧を取得できませんでした。時間をおいて再読み込みしてください。
        </p>
        <Link href="/">録画一覧へ</Link>
      </main>
    );
  }
  return (
    <main className="mx-auto w-full max-w-5xl space-y-6 p-8">
      <h1 className="text-2xl">コレクション・手動タグ</h1>
      <Link href="/" className="text-link underline">
        録画一覧へ
      </Link>
      <OrganizationManager options={options} />
    </main>
  );
}

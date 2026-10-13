import Link from "next/link";
import { LibraryHealthDashboard } from "@/components/library-health-dashboard";
import { getLibraryHealth } from "@/lib/api/health";
export default async function HealthPage() {
  const initial = await getLibraryHealth().catch(() => null);
  return <main className="mx-auto flex w-full max-w-5xl flex-col gap-6 px-6 py-10"><header><Link className="underline" href="/">録画一覧へ戻る</Link><h1 className="mt-4 text-3xl font-bold">ライブラリの状態</h1></header><LibraryHealthDashboard initial={initial} /></main>;
}

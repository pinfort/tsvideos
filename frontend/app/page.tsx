import Link from "next/link";
import { ContinueWatching } from "@/components/continue-watching";
import {
  parseProgramQuery,
  type ParsedProgramQuery,
  type ProgramQueryParams,
} from "@/lib/program-query";
import { Pager } from "@/components/pager";
import { BulkPrograms } from "@/components/bulk-programs";
import { SearchForm } from "@/components/search-form";
import { getPrograms } from "@/lib/api/programs";
import { ApiError } from "@/lib/api/client";
import type { SearchProgramsResponse } from "@/lib/api/types";

export default async function HomePage({
  searchParams,
}: {
  searchParams: Promise<ProgramQueryParams>;
}) {
  const values = await searchParams;
  let query: ParsedProgramQuery | undefined;
  let validationError: string | undefined;
  try {
    query = parseProgramQuery(values);
  } catch (error) {
    validationError =
      error instanceof Error ? error.message : "検索条件を確認してください。";
  }
  let result: SearchProgramsResponse | undefined;
  let apiError: string | undefined;
  if (query) {
    try {
      result = await getPrograms(query);
    } catch (error) {
      apiError =
        error instanceof ApiError && error.status === 400
          ? "検索条件が不正です。入力内容を確認してください。"
          : "検索結果を取得できませんでした。時間をおいて再度検索してください。";
    }
  }
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-6 px-6 py-10">
      <header>
        <Link href="/duplicates" className="mr-4 mb-4 inline-block underline">重複録画のレビュー</Link>
        <Link href="/storage" className="mb-4 inline-block underline">ストレージ概要</Link>
        {/* Keyed on the URL params so the uncontrolled inputs reset their defaults on navigation. */}
        <SearchForm key={JSON.stringify(values)} values={values} />
      </header>
      <ContinueWatching />
      <main>
        {validationError || apiError ? (
          <p
            role="alert"
            className="rounded border border-red-300 p-4 text-red-700 dark:text-red-300"
          >
            {validationError || apiError}
          </p>
        ) : null}
        {result && (
          <>
            <p className="mb-3 text-sm text-zinc-500">
              全{result.total}件（{result.offset}件スキップ・
              {result.programs.length}件表示）
            </p>
            {result.programs.length ? (
              <BulkPrograms key={JSON.stringify(values)} programs={result.programs} />
            ) : (
              <p className="rounded border border-zinc-200 p-6">
                条件に一致する録画がありません。検索条件やページを変更してください。
              </p>
            )}
          </>
        )}
      </main>
      {result && query && (
        <footer>
          <Pager query={query} total={result.total} />
        </footer>
      )}
    </div>
  );
}

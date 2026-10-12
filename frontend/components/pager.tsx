import Link from "next/link";
import {
  programQueryParams,
  type ParsedProgramQuery,
} from "@/lib/program-query";

export function Pager({
  query,
  total,
}: {
  query: ParsedProgramQuery;
  total: number;
}) {
  const { limit, offset } = query;
  const href = (next: number) =>
    `/?${programQueryParams({ ...query, offset: next })}`;
  return (
    <nav
      aria-label="検索結果のページ"
      className="flex items-center justify-center gap-4 py-4 text-sm"
    >
      {offset > 0 ? (
        <Link
          href={href(Math.max(offset - limit, 0))}
          aria-label="前のページ"
          className="text-blue-600 hover:underline"
        >
          &lt;&lt;
        </Link>
      ) : (
        <span aria-disabled="true" className="text-zinc-400">
          &lt;&lt;
        </span>
      )}
      <span>{Math.floor(offset / limit) + 1}</span>
      {offset + limit < total && offset + limit <= 2_147_483_647 ? (
        <Link
          href={href(offset + limit)}
          aria-label="次のページ"
          className="text-blue-600 hover:underline"
        >
          &gt;&gt;
        </Link>
      ) : (
        <span aria-disabled="true" className="text-zinc-400">
          &gt;&gt;
        </span>
      )}
    </nav>
  );
}

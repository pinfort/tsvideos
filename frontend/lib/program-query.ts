import type { ProgramSearchQuery } from "./api/types";

export type ProgramQueryParams = Record<string, string | string[] | undefined>;
export type ParsedProgramQuery = ProgramSearchQuery & {
  limit: number;
  offset: number;
};

export function programQueryParams(query: ProgramSearchQuery): URLSearchParams {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== "") params.set(key, String(value));
  }
  return params;
}

export function parseProgramQuery(
  params: ProgramQueryParams,
): ParsedProgramQuery {
  const value = (key: string): string | undefined => {
    const v = params[key];
    if (Array.isArray(v)) throw new Error("同じ検索条件を複数指定できません。");
    return v === "" ? undefined : v;
  };
  const integer = (
    key: string,
    min: number,
    max: number,
    fallback?: number,
  ) => {
    const v = value(key);
    if (v === undefined) return fallback;
    if (!/^\d+$/.test(v) || Number(v) < min || Number(v) > max)
      throw new Error(`${key} は ${min}〜${max} の整数で指定してください。`);
    return Number(v);
  };
  const option = <T extends string>(
    key: string,
    values: readonly T[],
    fallback?: T,
  ): T | undefined => {
    const v = value(key);
    if (v === undefined) return fallback;
    if (!values.includes(v as T))
      throw new Error("状態・ファイル形式・並び順の指定が不正です。");
    return v as T;
  };
  const date = (key: string) => {
    const v = value(key);
    if (v === undefined) return undefined;
    const match =
      /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?$/.exec(
        v,
      );
    if (!match)
      throw new Error(
        "録画日時はタイムゾーンを含まない有効な日時を指定してください。",
      );
    const [, year, month, day, hour, minute, second = "0"] = match;
    const y = Number(year),
      m = Number(month),
      d = Number(day);
    const leap = y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0);
    const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
    if (
      m < 1 ||
      m > 12 ||
      d < 1 ||
      d > days[m - 1] ||
      Number(hour) > 23 ||
      Number(minute) > 59 ||
      Number(second) > 59
    )
      throw new Error("録画日時が有効な日時ではありません。");
    return v;
  };
  const query: ParsedProgramQuery = {
    name: value("name"),
    channel: value("channel"),
    collectionId: integer("collectionId", 1, Number.MAX_SAFE_INTEGER),
    tagId: integer("tagId", 1, Number.MAX_SAFE_INTEGER),
    recordedFrom: date("recordedFrom"),
    recordedTo: date("recordedTo"),
    status: option("status", ["REGISTERED", "COMPLETED", "ERROR"] as const),
    minDrops: integer("minDrops", 0, 2_147_483_647),
    maxDrops: integer("maxDrops", 0, 2_147_483_647),
    fileType: option("fileType", ["TS", "ENCODED"] as const),
    sort: option(
      "sort",
      ["RECORDED_AT", "DURATION", "SIZE"] as const,
      "RECORDED_AT",
    ),
    direction: option("direction", ["ASC", "DESC"] as const, "DESC"),
    limit: integer("limit", 1, 500, 10)!,
    offset: integer("offset", 0, 2_147_483_647, 0)!,
  };
  // Compare local time at nanosecond precision without server/browser timezone conversion.
  const canonical = (v: string) =>
    `${v.slice(0, 16)}:${v.slice(17, 19) || "00"}.${(v.split(".")[1] || "").padEnd(9, "0")}`;
  if (
    query.recordedFrom &&
    query.recordedTo &&
    canonical(query.recordedFrom) >= canonical(query.recordedTo)
  )
    throw new Error("録画開始日時は終了日時より前にしてください。");
  if (
    query.minDrops !== undefined &&
    query.maxDrops !== undefined &&
    query.minDrops > query.maxDrops
  )
    throw new Error("ドロップ数の下限は上限以下にしてください。");
  return query;
}

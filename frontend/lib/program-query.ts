export function parseProgramQuery(params: { name?: string; limit?: string; offset?: string }) {
  const integer = (value: string | undefined, fallback: number) => {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? Math.trunc(parsed) : fallback;
  };
  return {
    name: params.name ?? "",
    limit: Math.min(Math.max(integer(params.limit, 10) || 10, 1), 100),
    offset: Math.min(Math.max(integer(params.offset, 0), 0), 2_147_483_647),
  };
}

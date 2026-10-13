import { getProgramDetail } from "@/lib/api/programs";

export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const { id } = await context.params;
  const number = Number(id);
  if (!/^\d+$/.test(id) || !Number.isSafeInteger(number) || number <= 0)
    return Response.json({ error: "Invalid program ID" }, { status: 400 });
  try {
    const detail = await getProgramDetail(number);
    return detail
      ? Response.json(detail)
      : Response.json({ error: "Not found" }, { status: 404 });
  } catch {
    return Response.json(
      { error: "Program metadata unavailable" },
      { status: 502 },
    );
  }
}

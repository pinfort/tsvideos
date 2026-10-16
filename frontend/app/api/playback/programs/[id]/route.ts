import { getProgramDetail } from "@/lib/api/programs";
import { parsePositiveId } from "@/lib/playback";

export async function GET(
  _request: Request,
  context: { params: Promise<{ id: string }> },
) {
  const id = parsePositiveId((await context.params).id);
  if (!id)
    return Response.json({ error: "Invalid program ID" }, { status: 400 });

  try {
    const detail = await getProgramDetail(id);
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

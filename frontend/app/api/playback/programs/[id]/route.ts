import { getProgramDetail } from "@/lib/api/programs";
import type { PlaybackMetadataResponse } from "@/lib/api/types";

// Availability must reflect current backend state, never a cached response.
export const dynamic = "force-dynamic";
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
    if (!detail)
      return Response.json({ error: "Not found" }, { status: 404 });
    // Only expose what the availability check needs, not full program detail.
    const body: PlaybackMetadataResponse = {
      videoFiles: detail.videoFiles.map(({ id, mp4, status }) => ({
        id,
        mp4,
        status,
      })),
    };
    return Response.json(body);
  } catch {
    return Response.json(
      { error: "Program metadata unavailable" },
      { status: 502 },
    );
  }
}

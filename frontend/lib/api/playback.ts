import { ApiError } from "./client";
import type { ProgramDetailResponse } from "./types";

/** Same-origin metadata only; video bytes continue to stream directly from the backend. */
export async function getPlaybackProgramDetail(
  id: number,
): Promise<ProgramDetailResponse | null> {
  const response = await fetch(`/api/playback/programs/${id}`, {
    cache: "no-store",
  });
  if (response.status === 404) return null;
  if (!response.ok)
    throw new ApiError(response.status, "Playback metadata unavailable");
  return response.json() as Promise<ProgramDetailResponse>;
}

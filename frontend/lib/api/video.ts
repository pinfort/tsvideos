import { fetchJson, getApiBaseUrl } from "./client";
import type { VideoChaptersResponse } from "./types";

/** Chapters are optional, so an unresponsive API must not hold the player page for a whole network timeout. */
export const VIDEO_CHAPTERS_TIMEOUT_MS = 3000;

export function getVideoStreamUrl(fileId: number): string {
  return `${getApiBaseUrl()}/api/v1/video/${fileId}/stream`;
}

/**
 * Throws ApiError on any non-2xx response (404 when the video is not streamable),
 * and a TimeoutError DOMException when no response arrives within VIDEO_CHAPTERS_TIMEOUT_MS.
 */
export async function getVideoChapters(
  fileId: number,
): Promise<VideoChaptersResponse> {
  return fetchJson<VideoChaptersResponse>(`/api/v1/video/${fileId}/chapters`, {
    signal: AbortSignal.timeout(VIDEO_CHAPTERS_TIMEOUT_MS),
  });
}

import { type ProgramDetailResponse } from "./api/types";

export const PLAYBACK_KEY = "tsvideos.playback.v1";
export const PLAYBACK_EVENT = "tsvideos-playback-change";
export interface PlaybackEntry {
  videoId: number;
  programId?: number;
  title: string;
  position: number;
  duration: number;
  updatedAt: number;
}
const positiveId = (value: unknown): value is number =>
  typeof value === "number" && Number.isSafeInteger(value) && value > 0;
export function completed(position: number, duration: number): boolean {
  return (
    duration > 0 && (position >= duration * 0.95 || duration - position <= 10)
  );
}
export function readPlayback(): PlaybackEntry[] {
  try {
    const parsed: unknown = JSON.parse(
      localStorage.getItem(PLAYBACK_KEY) || "[]",
    );
    if (!Array.isArray(parsed)) return [];
    const entries = parsed
      .filter((entry): entry is PlaybackEntry => {
        if (!entry || typeof entry !== "object") return false;
        const e = entry as PlaybackEntry;
        return (
          positiveId(e.videoId) &&
          (e.programId === undefined || positiveId(e.programId)) &&
          typeof e.title === "string" &&
          e.title.length <= 300 &&
          Number.isFinite(e.position) &&
          e.position > 0 &&
          Number.isFinite(e.duration) &&
          e.duration > 0 &&
          e.position < e.duration &&
          !completed(e.position, e.duration) &&
          Number.isFinite(e.updatedAt) &&
          e.updatedAt >= 0
        );
      })
      .sort((a, b) => b.updatedAt - a.updatedAt);
    return entries
      .filter(
        (e, i) =>
          entries.findIndex((other) => other.videoId === e.videoId) === i,
      )
      .slice(0, 50);
  } catch {
    return [];
  }
}
function writePlayback(entries: PlaybackEntry[]): void {
  try {
    localStorage.setItem(PLAYBACK_KEY, JSON.stringify(entries.slice(0, 50)));
    window.dispatchEvent(new Event(PLAYBACK_EVENT));
  } catch {
    /* Playback remains usable when browser storage is unavailable. */
  }
}
export function removePlayback(videoId: number): void {
  writePlayback(readPlayback().filter((entry) => entry.videoId !== videoId));
}
export function savePlayback(entry: PlaybackEntry): void {
  if (
    !positiveId(entry.videoId) ||
    !Number.isFinite(entry.duration) ||
    entry.duration <= 0 ||
    !Number.isFinite(entry.position) ||
    entry.position < 0
  )
    return;
  const others = readPlayback().filter((e) => e.videoId !== entry.videoId);
  writePlayback(
    entry.position === 0 || completed(entry.position, entry.duration)
      ? others
      : [{ ...entry, title: entry.title.slice(0, 300) }, ...others],
  );
}
export function playbackHref(entry: PlaybackEntry): string {
  const params = new URLSearchParams({ title: entry.title });
  if (entry.programId) params.set("programId", String(entry.programId));
  return `/video/${entry.videoId}/view?${params}`;
}
export function hasPlayableFile(
  detail: ProgramDetailResponse | null,
  videoId: number,
): boolean {
  return !!detail?.videoFiles.some(
    (file) => file.id === videoId && file.mp4 && file.status === "FILE_MOVED",
  );
}

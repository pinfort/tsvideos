import { type PlaybackMetadataResponse } from "./api/types";

export const PLAYBACK_KEY = "tsvideos.playback.v1";
export const PLAYBACK_EVENT = "tsvideos-playback-change";
const MAX_TITLE_LENGTH = 300;
const MAX_ENTRIES = 50;
/** A video counts as finished at this fraction of its duration... */
const COMPLETED_RATIO = 0.95;
/** ...or within this many seconds of the end. */
const COMPLETED_TAIL_SECONDS = 10;
/** Minimum interval between progress saves during playback. */
export const SAVE_INTERVAL_MS = 5000;

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

/** Parses a route/query value such as "42" into a positive safe integer ID. */
export function parsePositiveId(raw: unknown): number | undefined {
  if (typeof raw !== "string" || !/^\d+$/.test(raw)) return undefined;
  const id = Number(raw);
  return positiveId(id) ? id : undefined;
}

/** The view page's title query param, capped and with a fallback for direct links. */
export function playbackTitle(raw: unknown, videoId: number): string {
  return typeof raw === "string" && raw.trim()
    ? raw.slice(0, MAX_TITLE_LENGTH)
    : `動画 #${videoId}`;
}

export function completed(position: number, duration: number): boolean {
  return (
    duration > 0 &&
    (position >= duration * COMPLETED_RATIO ||
      duration - position <= COMPLETED_TAIL_SECONDS)
  );
}

/**
 * Invalid or completed entries are only filtered out here; they stay in
 * storage until the next write, which persists the filtered list.
 */
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
          e.title.length <= MAX_TITLE_LENGTH &&
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
    // Keep only the newest entry per video.
    const seen = new Set<number>();
    return entries
      .filter((e) => !seen.has(e.videoId) && !!seen.add(e.videoId))
      .slice(0, MAX_ENTRIES);
  } catch {
    return [];
  }
}

// Writes are read-modify-write without locking: two tabs saving different
// videos at the same moment can drop one update. Acceptable for resume points.
function writePlayback(entries: PlaybackEntry[]): void {
  try {
    localStorage.setItem(
      PLAYBACK_KEY,
      JSON.stringify(entries.slice(0, MAX_ENTRIES)),
    );
    window.dispatchEvent(new Event(PLAYBACK_EVENT));
  } catch {
    /* Playback remains usable when browser storage is unavailable. */
  }
}

export function removePlayback(videoId: number): void {
  writePlayback(readPlayback().filter((entry) => entry.videoId !== videoId));
}

/**
 * Saves a position, or drops the entry when the position is 0 (restarted) or
 * completed, so cleanup saves after restart/ended never resurrect history.
 */
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
      : [{ ...entry, title: entry.title.slice(0, MAX_TITLE_LENGTH) }, ...others],
  );
}

export function playbackHref(entry: PlaybackEntry): string {
  const params = new URLSearchParams({ title: entry.title });
  if (entry.programId) params.set("programId", String(entry.programId));
  return `/video/${entry.videoId}/view?${params}`;
}

/** The stream endpoint only serves files that have been moved to the video store. */
export function hasPlayableFile(
  detail: PlaybackMetadataResponse | null,
  videoId: number,
): boolean {
  return !!detail?.videoFiles.some(
    (file) => file.id === videoId && file.mp4 && file.status === "FILE_MOVED",
  );
}

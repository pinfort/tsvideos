"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { getPlaybackProgramDetail } from "@/lib/api/playback";
import type { ProgramDetailResponse } from "@/lib/api/types";
import {
  hasPlayableFile,
  PLAYBACK_EVENT,
  PLAYBACK_KEY,
  playbackHref,
  readPlayback,
  removePlayback,
  type PlaybackEntry,
} from "@/lib/playback";

type Check = "missing" | "unknown" | "available";

export function ContinueWatching() {
  const [entries, setEntries] = useState<PlaybackEntry[]>([]);
  // Metadata per programId: null = confirmed 404, "failed" = check unavailable.
  const [details, setDetails] = useState<
    Record<number, ProgramDetailResponse | null | "failed">
  >({});
  // Each program is checked once per page load, not on every history change.
  const requested = useRef(new Set<number>());

  useEffect(() => {
    const refresh = () => setEntries(readPlayback());
    const storage = (event: StorageEvent) => {
      if (!event.key || event.key === PLAYBACK_KEY) refresh();
    };
    refresh();
    window.addEventListener(PLAYBACK_EVENT, refresh);
    window.addEventListener("storage", storage);
    return () => {
      window.removeEventListener(PLAYBACK_EVENT, refresh);
      window.removeEventListener("storage", storage);
    };
  }, []);

  useEffect(() => {
    for (const { programId } of entries) {
      if (!programId || requested.current.has(programId)) continue;
      requested.current.add(programId);
      getPlaybackProgramDetail(programId)
        .catch(() => "failed" as const)
        .then((detail) =>
          setDetails((prev) => ({ ...prev, [programId]: detail })),
        );
    }
  }, [entries]);

  const check = (entry: PlaybackEntry): Check | undefined => {
    if (!entry.programId) return "unknown";
    const detail = details[entry.programId];
    if (detail === undefined) return undefined;
    if (detail === "failed") return "unknown";
    return hasPlayableFile(detail, entry.videoId) ? "available" : "missing";
  };

  if (!entries.length) return null;

  return (
    <section aria-label="続きから視聴" className="rounded border p-4">
      <h2 className="mb-3 text-lg font-semibold">続きから視聴</h2>
      <ul className="flex flex-col gap-3">
        {entries.map((entry) => (
          <li key={entry.videoId} className="flex flex-wrap items-center gap-3">
            {check(entry) === "missing" ? (
              <span>{entry.title}（削除済み・利用不可）</span>
            ) : (
              <Link
                className="text-blue-600 underline"
                href={playbackHref(entry)}
              >
                {entry.title}
              </Link>
            )}
            <span>
              {Math.floor(entry.position / 60)}分 /{" "}
              {Math.ceil(entry.duration / 60)}分
            </span>
            {check(entry) === "unknown" && (
              <span className="text-sm">利用可否未確認</span>
            )}
            <button
              type="button"
              className="underline"
              aria-label={`${entry.title}を履歴から削除`}
              onClick={() => removePlayback(entry.videoId)}
            >
              履歴から削除
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

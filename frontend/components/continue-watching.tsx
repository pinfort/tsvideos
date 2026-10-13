"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { getPlaybackProgramDetail } from "@/lib/api/playback";
import {
  hasPlayableFile,
  PLAYBACK_EVENT,
  PLAYBACK_KEY,
  playbackHref,
  readPlayback,
  removePlayback,
  type PlaybackEntry,
} from "@/lib/playback";

export function ContinueWatching() {
  const [entries, setEntries] = useState<PlaybackEntry[]>([]);
  const [checks, setChecks] = useState<
    Record<number, "missing" | "unknown" | "available">
  >({});
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
    let active = true;
    const ids = [
      ...new Set(entries.flatMap((e) => (e.programId ? [e.programId] : []))),
    ];
    Promise.all(
      ids.map(async (id) => {
        try {
          return { id, detail: await getPlaybackProgramDetail(id) };
        } catch {
          return { id, detail: undefined };
        }
      }),
    ).then((results) => {
      if (!active) return;
      const next: typeof checks = {};
      for (const entry of entries) {
        const result = results.find((r) => r.id === entry.programId);
        next[entry.videoId] =
          !result || result.detail === undefined
            ? "unknown"
            : hasPlayableFile(result.detail, entry.videoId)
              ? "available"
              : "missing";
      }
      setChecks(next);
    });
    return () => {
      active = false;
    };
  }, [entries]);
  if (!entries.length) return null;
  return (
    <section aria-label="続きから視聴" className="rounded border p-4">
      <h2 className="mb-3 text-lg font-semibold">続きから視聴</h2>
      <ul className="flex flex-col gap-3">
        {entries.map((entry) => (
          <li key={entry.videoId} className="flex flex-wrap items-center gap-3">
            {checks[entry.videoId] === "missing" ? (
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
            {checks[entry.videoId] === "unknown" && (
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

"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { getVideoStreamUrl } from "@/lib/api/video";
import {
  completed,
  readPlayback,
  removePlayback,
  savePlayback,
} from "@/lib/playback";

export function VideoPlayer({
  videoId,
  programId,
  title,
}: {
  videoId: number;
  programId?: number;
  title: string;
}) {
  const video = useRef<HTMLVideoElement>(null);
  const loaded = useRef(false);
  const lastSaved = useRef(0);
  // Read by restore so a speed change does not re-run the restore effect.
  const speedRef = useRef("1");
  const [unavailable, setUnavailable] = useState(false);
  const [speed, setSpeed] = useState("1");
  const [notice, setNotice] = useState("");
  const src = getVideoStreamUrl(videoId);

  const restore = useCallback(() => {
    const player = video.current;
    if (!player || !Number.isFinite(player.duration) || player.duration <= 0)
      return;
    if (!loaded.current) {
      const saved = readPlayback().find((e) => e.videoId === videoId);
      if (saved) {
        const position = Math.min(saved.position, player.duration);
        if (completed(position, player.duration)) removePlayback(videoId);
        else {
          player.currentTime = position;
          setNotice("前回の続きから再開します。");
        }
      }
      loaded.current = true;
    }
    player.playbackRate = Number(speedRef.current);
    setUnavailable(false);
  }, [videoId]);

  // Metadata or an error may already be present before hydration attaches handlers.
  useEffect(() => {
    if (video.current?.error) setUnavailable(true);
    else if ((video.current?.readyState ?? 0) >= 1) restore();
  }, [restore]);

  const saveCurrent = useCallback(
    (player: HTMLVideoElement | null) => {
      if (!player || !loaded.current || player.error) return;
      // savePlayback drops position 0 / completed entries, so saves after
      // restart or ended (including the unmount save) remove history.
      savePlayback({
        videoId,
        programId,
        title,
        position: player.currentTime,
        duration: player.duration,
        updatedAt: Date.now(),
      });
      lastSaved.current = Date.now();
    },
    [videoId, programId, title],
  );
  const persist = () => saveCurrent(video.current);

  useEffect(() => {
    const player = video.current;
    const save = () => saveCurrent(player);
    const hide = () => {
      if (document.visibilityState === "hidden") save();
    };
    window.addEventListener("pagehide", save);
    document.addEventListener("visibilitychange", hide);
    return () => {
      save();
      window.removeEventListener("pagehide", save);
      document.removeEventListener("visibilitychange", hide);
    };
  }, [saveCurrent]);

  return (
    <section className="flex w-full flex-col gap-4" aria-label="動画プレーヤー">
      <h1 className="break-words text-xl font-semibold">{title}</h1>
      <video
        ref={video}
        controls
        playsInline
        preload="metadata"
        src={src}
        className="aspect-video w-full max-w-full bg-black"
        aria-label={title}
        onLoadedMetadata={restore}
        onTimeUpdate={() => {
          if (Date.now() - lastSaved.current >= 5000) persist();
        }}
        onPause={persist}
        onSeeked={persist}
        onEnded={() => {
          removePlayback(videoId);
          setNotice("視聴を完了しました。");
        }}
        onError={() => setUnavailable(true)}
      >
        <a href={src}>動画をダウンロード</a>
      </video>
      <div className="flex flex-wrap items-center gap-4">
        <label>
          再生速度{" "}
          <select
            value={speed}
            onChange={(event) => {
              setSpeed(event.target.value);
              speedRef.current = event.target.value;
              if (video.current)
                video.current.playbackRate = Number(event.target.value);
            }}
            className="rounded border p-2"
          >
            {[0.5, 0.75, 1, 1.25, 1.5, 2].map((rate) => (
              <option key={rate} value={rate}>
                {rate}倍
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="rounded border px-3 py-2"
          onClick={() => {
            if (video.current) video.current.currentTime = 0;
            removePlayback(videoId);
            setNotice("最初から再生します。");
          }}
        >
          最初から再生
        </button>
      </div>
      <p role="status">{notice}</p>
      {unavailable && (
        <div role="alert" className="rounded border p-4">
          <p>
            動画を再生できません。ファイルの削除や保存先への接続を確認してください。
          </p>
          <button
            type="button"
            className="underline"
            onClick={() => {
              removePlayback(videoId);
              setNotice("視聴履歴から削除しました。");
            }}
          >
            履歴から削除
          </button>
        </div>
      )}
      <p className="text-sm text-zinc-500">
        再生位置はこのブラウザーに保存されます。他の端末とは同期しません。
      </p>
    </section>
  );
}

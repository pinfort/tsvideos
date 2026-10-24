import type { VideoChapter } from "@/lib/api/types";

/** `h:mm:ss`, rounding down to the second. */
export function formatChapterTime(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

/**
 * Index of the chapter playing at `time`: the last one that has started and not
 * yet ended. Null in a gap between chapters or past the last one.
 */
export function currentChapterIndex(
  chapters: VideoChapter[],
  time: number,
): number | null {
  for (let i = chapters.length - 1; i >= 0; i--) {
    const chapter = chapters[i];
    if (chapter.startSeconds <= time && time < chapter.endSeconds)
      return chapter.index;
  }
  return null;
}

/** join_logo_scp labels CM sections `CM` / `CM?`; they are only shown dimmer. */
function isCommercial(chapter: VideoChapter): boolean {
  return chapter.title?.startsWith("CM") ?? false;
}

export function VideoChapters({
  chapters,
  currentIndex,
  onSelect,
  disabled = false,
}: {
  chapters: VideoChapter[];
  currentIndex: number | null;
  onSelect: (chapter: VideoChapter) => void;
  disabled?: boolean;
}) {
  if (chapters.length === 0) return null;
  return (
    <nav aria-label="チャプタ" className="flex flex-col gap-2">
      <h2 className="text-sm font-semibold">チャプタ</h2>
      <ol className="flex max-h-72 flex-col overflow-y-auto rounded border">
        {chapters.map((chapter, position) => {
          const current = chapter.index === currentIndex;
          const commercial = isCommercial(chapter);
          return (
            <li key={chapter.index}>
              <button
                type="button"
                aria-current={current ? "true" : undefined}
                data-kind={commercial ? "cm" : "main"}
                disabled={disabled}
                onClick={() => onSelect(chapter)}
                className={[
                  "flex w-full gap-3 border-l-4 px-3 py-1.5 text-left hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50",
                  current ? "border-primary bg-muted font-semibold" : "border-transparent",
                  commercial ? "text-sm text-muted-foreground" : "",
                ].join(" ")}
              >
                <span className="font-mono tabular-nums">
                  {formatChapterTime(chapter.startSeconds)}
                </span>
                <span className="break-words">
                  {chapter.title ?? `チャプタ ${position + 1}`}
                </span>
              </button>
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

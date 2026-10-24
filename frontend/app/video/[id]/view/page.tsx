import { notFound } from "next/navigation";
import { VideoPlayer } from "@/components/video-player";
import type { VideoChapter } from "@/lib/api/types";
import { getVideoChapters } from "@/lib/api/video";
import { parsePositiveId, playbackTitle } from "@/lib/playback";

/** Chapters are optional: any failure, 404 or unprobed file just shows none, and playback is unaffected. */
async function loadChapters(fileId: number): Promise<VideoChapter[]> {
  try {
    const response = await getVideoChapters(fileId);
    return response.probed && Array.isArray(response.chapters)
      ? response.chapters
      : [];
  } catch {
    return [];
  }
}

export default async function VideoViewPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id } = await params;
  const fileId = parsePositiveId(id);
  if (!fileId) notFound();

  const query = await searchParams;
  const programId = parsePositiveId(query?.programId);
  const title = playbackTitle(query?.title, fileId);
  const chapters = await loadChapters(fileId);

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <VideoPlayer
        key={fileId}
        videoId={fileId}
        programId={programId}
        title={title}
        chapters={chapters}
      />
    </div>
  );
}

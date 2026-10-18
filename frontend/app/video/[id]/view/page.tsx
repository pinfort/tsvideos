import { notFound } from "next/navigation";
import { VideoPlayer } from "@/components/video-player";
import { parsePositiveId, playbackTitle } from "@/lib/playback";

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

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-4 px-4 py-6 sm:px-6">
      <VideoPlayer
        key={fileId}
        videoId={fileId}
        programId={programId}
        title={title}
      />
    </div>
  );
}

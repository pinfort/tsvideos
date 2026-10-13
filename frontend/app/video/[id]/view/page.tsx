import { notFound } from "next/navigation";
import { VideoPlayer } from "@/components/video-player";
export default async function VideoViewPage({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
  const { id } = await params;
  const fileId = Number(id);
  if (!/^\d+$/.test(id) || !Number.isSafeInteger(fileId) || fileId <= 0)
    notFound();
  const query = await searchParams;
  const raw = query?.programId;
  const programId =
    typeof raw === "string" &&
    /^\d+$/.test(raw) &&
    Number.isSafeInteger(Number(raw)) &&
    Number(raw) > 0
      ? Number(raw)
      : undefined;
  const title =
    typeof query?.title === "string" && query.title.trim()
      ? query.title.slice(0, 300)
      : `動画 #${fileId}`;
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

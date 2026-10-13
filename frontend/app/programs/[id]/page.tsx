import { notFound } from "next/navigation";
import { ProgramDetailTable } from "@/components/program-detail-table";
import { VideoFilesTable } from "@/components/video-files-table";
import { getProgramDetail } from "@/lib/api/programs";

export default async function ProgramDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;
  const programId = Number(id);
  if (Number.isNaN(programId)) notFound();

  const detail = await getProgramDetail(programId);
  if (!detail) notFound();

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-6 px-6 py-10">
      <main className="flex flex-col gap-6">
        <ProgramDetailTable program={detail.program} />
        <hr className="border-zinc-200 dark:border-zinc-800" />
        <VideoFilesTable files={detail.videoFiles} programId={programId} title={detail.program.name} />
      </main>
    </div>
  );
}

import Link from "next/link";
import type { CreatedFile } from "@/lib/api/types";

function viewHref(file: CreatedFile, programId?: number, title?: string) {
  if (!programId) return `/video/${file.id}/view`;
  // Without a program name, omit title so the viewer falls back to "動画 #id"
  // rather than putting the NAS file path in the URL and history.
  const params = new URLSearchParams({ programId: String(programId) });
  if (title) params.set("title", title);
  return `/video/${file.id}/view?${params}`;
}

export function VideoFilesTable({
  files,
  programId,
  title,
}: {
  files: CreatedFile[];
  programId?: number;
  title?: string;
}) {
  return (
    <table className="min-w-full divide-y divide-zinc-200 text-sm dark:divide-zinc-800">
      <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {files.map((file) => (
          <tr key={file.id}>
            <td className="px-4 py-2">{file.file}</td>
            <td className="px-4 py-2 whitespace-nowrap">
              {file.width != null && file.height != null ? `${file.width}×${file.height}` : "-"}
            </td>
            <td className="px-4 py-2">
              {file.mp4 ? (
                <Link
                  href={viewHref(file, programId, title)}
                  className="text-blue-600 hover:underline dark:text-blue-400"
                >
                  視聴
                </Link>
              ) : (
                "動画ファイルでありません"
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

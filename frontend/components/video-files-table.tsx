import Link from "next/link";
import type { CreatedFile } from "@/lib/api/types";

export function VideoFilesTable({ files, programId, title }: { files: CreatedFile[]; programId?: number; title?: string }) {
  return (
    <table className="min-w-full divide-y divide-zinc-200 text-sm dark:divide-zinc-800">
      <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {files.map((file) => (
          <tr key={file.id}>
            <td className="px-4 py-2">{file.file}</td>
            <td className="px-4 py-2">
              {file.mp4 ? (
                <Link
                  href={programId ? `/video/${file.id}/view?${new URLSearchParams({ programId: String(programId), title: title || file.file })}` : `/video/${file.id}/view`}
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

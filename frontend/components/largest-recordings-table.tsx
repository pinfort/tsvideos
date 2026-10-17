import Link from "next/link";
import type { StorageRecording } from "@/lib/api/storage";
import { formatBytes } from "@/lib/format-bytes";

export function LargestRecordingsTable({ recordings }: { recordings: StorageRecording[] }) {
  if (recordings.length === 0) {
    return <p className="mt-4">録画はまだありません。</p>;
  }
  return (
    <div className="mt-4 overflow-x-auto">
      <table className="w-full text-left">
        <thead>
          <tr className="border-b">
            <th className="p-2">番組</th>
            <th className="p-2">登録容量</th>
          </tr>
        </thead>
        <tbody>
          {recordings.map((recording) => (
            <tr key={recording.id} className="border-b">
              <td className="p-2">
                <Link className="underline" href={`/programs/${recording.id}`}>
                  {recording.name}
                </Link>
              </td>
              <td className="whitespace-nowrap p-2 font-mono">{formatBytes(recording.registeredBytes)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

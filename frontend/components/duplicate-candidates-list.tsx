import Link from "next/link";
import type { StorageDuplicateCandidate } from "@/lib/api/storage";
import { formatBytes } from "@/lib/format-bytes";

export function DuplicateCandidatesList({ candidates }: { candidates: StorageDuplicateCandidate[] }) {
  if (candidates.length === 0) {
    return <p className="mt-4">重複の整理候補はありません。</p>;
  }
  return (
    <ul className="mt-4 space-y-4">
      {/* Duplicate groups never share a program, so the retained id is unique. */}
      {candidates.map((group) => (
        <li key={group.retained.id} className="rounded-lg border p-4">
          <p className="text-sm text-gray-500">{group.reason === "SAME_BROADCAST" ? "同じ放送" : "録画時間の重複"}</p>
          <p className="mt-2">
            保持を推薦：
            <Link className="font-semibold underline" href={`/programs/${group.retained.id}`}>
              {group.retained.name}
            </Link>
          </p>
          <ul className="mt-2 list-inside list-disc">
            {group.removable.map((recording) => (
              <li key={recording.id}>
                整理候補：
                <Link className="underline" href={`/programs/${recording.id}`}>
                  {recording.name}
                </Link>
                （登録 {formatBytes(recording.registeredBytes)}）
              </li>
            ))}
          </ul>
        </li>
      ))}
    </ul>
  );
}

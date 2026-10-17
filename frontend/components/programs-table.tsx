import Link from "next/link";
import { programStatusToJapanese } from "@/lib/api/status-labels";
import type { Program } from "@/lib/api/types";

export function ProgramsTable({ programs, selected, onSelect }: { programs: Program[]; selected?: number[]; onSelect?: (id: number, checked: boolean) => void }) {
  return (
    <table className="min-w-full divide-y divide-zinc-200 text-sm dark:divide-zinc-800">
      <thead>
        <tr>
          {onSelect && <th className="px-4 py-2">選択</th>}
          <th className="px-4 py-2 text-left font-medium">番号</th>
          <th className="px-4 py-2 text-left font-medium">ファイル名</th>
          <th className="px-4 py-2 text-left font-medium">executedFileId</th>
          <th className="px-4 py-2 text-left font-medium">状態</th>
          <th className="px-4 py-2 text-left font-medium">ドロップ数</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {programs.map((program) => (
          <tr key={program.id}>
            {onSelect && <td><input type="checkbox" aria-label={`録画 ${program.id} を選択`} checked={selected?.includes(program.id) ?? false} disabled={!selected?.includes(program.id) && (selected?.length ?? 0) >= 100} onChange={e => onSelect(program.id, e.target.checked)} /></td>}
            <td className="px-4 py-2">{program.id}</td>
            <td className="px-4 py-2">
              <Link
                href={`/programs/${program.id}`}
                className="text-blue-600 hover:underline dark:text-blue-400"
              >
                {program.name}
              </Link>
            </td>
            <td className="px-4 py-2">{program.executedFileId}</td>
            <td className="px-4 py-2">{programStatusToJapanese(program.status)}</td>
            <td className="px-4 py-2">{program.drops}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

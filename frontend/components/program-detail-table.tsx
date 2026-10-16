import { programStatusToJapanese } from "@/lib/api/status-labels";
import { detectionToJapanese, KNOWN_TAGS } from "@/lib/api/tags";
import type { ProgramDetail } from "@/lib/api/types";

export function ProgramDetailTable({ program }: { program: ProgramDetail }) {
  const rows: Array<[string, React.ReactNode]> = [
    ["番号", program.id],
    ["ファイル名", program.name],
    ["executedFileId", program.executedFileId],
    ["状態", programStatusToJapanese(program.status)],
    ["ドロップ数", program.drops],
    ...KNOWN_TAGS.map((knownTag): [string, React.ReactNode] => [
      knownTag.label,
      detectionToJapanese(program, knownTag),
    ]),
  ];

  return (
    <table className="min-w-full divide-y divide-zinc-200 text-sm dark:divide-zinc-800">
      <tbody className="divide-y divide-zinc-200 dark:divide-zinc-800">
        {rows.map(([label, value]) => (
          <tr key={label}>
            <td className="px-4 py-2 font-medium">{label}</td>
            <td className="px-4 py-2">{value}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

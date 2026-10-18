import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableHeader,
  TableBody,
  TableRow,
  TableHead,
  TableCell,
} from "@/components/ui/table";
import Link from "next/link";
import { programStatusToJapanese } from "@/lib/api/status-labels";
import type { Program } from "@/lib/api/types";

export function ProgramsTable({
  programs,
  selected,
  onSelect,
}: {
  programs: Program[];
  selected?: number[];
  onSelect?: (id: number, checked: boolean) => void;
}) {
  return (
    <Table className="min-w-[640px]" aria-label="録画一覧">
      <TableHeader>
        <TableRow>
          {onSelect && <TableHead className="px-4 py-2">選択</TableHead>}
          <TableHead className="px-4 py-2 text-left font-medium">
            番号
          </TableHead>
          <TableHead className="px-4 py-2 text-left font-medium">
            ファイル名
          </TableHead>
          <TableHead className="px-4 py-2 text-left font-medium">
            executedFileId
          </TableHead>
          <TableHead className="px-4 py-2 text-left font-medium">
            状態
          </TableHead>
          <TableHead className="px-4 py-2 text-left font-medium">
            ドロップ数
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {programs.map((program) => (
          <TableRow
            key={program.id}
            data-state={selected?.includes(program.id) ? "selected" : undefined}
          >
            {onSelect && (
              <TableCell>
                <input
                  type="checkbox"
                  aria-label={`録画 ${program.id} を選択`}
                  checked={selected?.includes(program.id) ?? false}
                  disabled={
                    !selected?.includes(program.id) &&
                    (selected?.length ?? 0) >= 100
                  }
                  onChange={(e) => onSelect(program.id, e.target.checked)}
                />
              </TableCell>
            )}
            <TableCell className="px-4 py-2">{program.id}</TableCell>
            <TableCell className="px-4 py-2">
              <Link
                href={`/programs/${program.id}`}
                className="text-blue-600 hover:underline dark:text-blue-400"
              >
                {program.name}
              </Link>
            </TableCell>
            <TableCell className="px-4 py-2">
              {program.executedFileId}
            </TableCell>
            <TableCell className="px-4 py-2">
              <Badge
                variant={
                  program.status === "ERROR"
                    ? "destructive"
                    : program.status === "COMPLETED"
                      ? "default"
                      : "secondary"
                }
              >
                {programStatusToJapanese(program.status)}
              </Badge>
            </TableCell>
            <TableCell className="px-4 py-2">{program.drops}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

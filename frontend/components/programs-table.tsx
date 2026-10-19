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
import type { Program, ProgramStatus } from "@/lib/api/types";

/** Upper bound on in-page bulk selection, shared with `BulkPrograms`. */
export const MAX_SELECTION = 100;

const statusBadgeVariant: Record<
  ProgramStatus,
  "default" | "secondary" | "destructive"
> = {
  REGISTERED: "secondary",
  COMPLETED: "default",
  ERROR: "destructive",
};

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
          {onSelect && <TableHead>選択</TableHead>}
          <TableHead>番号</TableHead>
          <TableHead>ファイル名</TableHead>
          <TableHead>executedFileId</TableHead>
          <TableHead>状態</TableHead>
          <TableHead>ドロップ数</TableHead>
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
                  className="size-4 accent-primary focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                  aria-label={`録画 ${program.id} を選択`}
                  checked={selected?.includes(program.id) ?? false}
                  disabled={
                    !selected?.includes(program.id) &&
                    (selected?.length ?? 0) >= MAX_SELECTION
                  }
                  onChange={(e) => onSelect(program.id, e.target.checked)}
                />
              </TableCell>
            )}
            <TableCell>{program.id}</TableCell>
            <TableCell>
              <Link
                href={`/programs/${program.id}`}
                className="text-blue-600 hover:underline dark:text-blue-400"
              >
                {program.name}
              </Link>
            </TableCell>
            <TableCell>{program.executedFileId}</TableCell>
            <TableCell>
              <Badge
                // The API may add statuses before this map does; status-labels falls back to 異常 too.
                variant={statusBadgeVariant[program.status] ?? "secondary"}
              >
                {programStatusToJapanese(program.status)}
              </Badge>
            </TableCell>
            <TableCell>{program.drops}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

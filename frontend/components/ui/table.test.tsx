import Link from "next/link";
import { render, screen, within } from "@testing-library/react";
import { expect, it } from "vitest";
import {
  Table,
  TableHeader,
  TableBody,
  TableHead,
  TableRow,
  TableCell,
  TableCaption,
} from "./table";

it("preserves table semantics and interactive cell content", () => {
  render(
    <Table>
      <TableCaption>録画一覧</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>番組名</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow>
          <TableCell>
            <Link href="/programs/1">録画1</Link>
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>,
  );
  const table = screen.getByRole("table", { name: "録画一覧" });
  expect(within(table).getByRole("columnheader")).toHaveTextContent("番組名");
  expect(within(table).getByRole("link")).toHaveAttribute(
    "href",
    "/programs/1",
  );
});

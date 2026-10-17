import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Pager } from "./pager";
import { parseProgramQuery } from "@/lib/program-query";

describe("Pager", () => {
  it("uses total at exact page boundaries", () => {
    render(<Pager query={parseProgramQuery({ limit: "10" })} total={10} />);
    expect(screen.getByText("<<")).not.toHaveAttribute("href");
    expect(screen.getByText(">>")).not.toHaveAttribute("href");
  });
  it("preserves every filter and sort while changing only offset", () => {
    const raw = {
      name: "番組",
      channel: "BS1",
      recordedFrom: "2026-10-01T00:00",
      recordedTo: "2026-11-01T00:00",
      status: "ERROR",
      minDrops: "0",
      maxDrops: "2",
      fileType: "TS",
      sort: "SIZE",
      direction: "ASC",
      limit: "10",
      offset: "20",
    };
    render(<Pager query={parseProgramQuery(raw)} total={31} />);
    const next = new URL(
      screen.getByRole("link", { name: "次のページ" }).getAttribute("href")!,
      "http://localhost",
    );
    expect(Object.fromEntries(next.searchParams)).toEqual({
      ...raw,
      offset: "30",
    });
    const prev = new URL(
      screen.getByRole("link", { name: "前のページ" }).getAttribute("href")!,
      "http://localhost",
    );
    expect(prev.searchParams.get("offset")).toBe("10");
  });
  it("allows returning from an empty beyond-end page", () => {
    render(<Pager query={parseProgramQuery({ offset: "100" })} total={1} />);
    expect(
      screen.getByRole("link", { name: "前のページ" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "次のページ" }),
    ).not.toBeInTheDocument();
  });
});

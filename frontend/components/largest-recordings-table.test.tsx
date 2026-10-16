import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { LargestRecordingsTable } from "./largest-recordings-table";

describe("LargestRecordingsTable", () => {
  it("links each recording and shows its registered bytes", () => {
    render(<LargestRecordingsTable recordings={[{ id: 7, name: "大きい番組", registeredBytes: 3000000000 }]} />);
    expect(screen.getByRole("link", { name: "大きい番組" })).toHaveAttribute("href", "/programs/7");
    expect(screen.getByText("3,000,000,000 bytes")).toBeInTheDocument();
  });

  it("says when there are no recordings", () => {
    render(<LargestRecordingsTable recordings={[]} />);
    expect(screen.getByText("録画はまだありません。")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});

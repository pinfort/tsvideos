import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ProgramDetail } from "@/lib/api/types";
import { ProgramDetailTable } from "./program-detail-table";

const detail: ProgramDetail = {
  id: 5,
  name: "Detail Program",
  executedFileId: 50,
  status: "ERROR",
  drops: 7,
  size: 2000,
  recordedAt: "2024-01-01T00:00:00Z",
  channel: "2",
  title: "Detail Program",
  channelName: "Channel 2",
  duration: 3600,
  createdFiles: [],
  tags: ["ews"],
  checks: ["emergency_broadcast"],
};

describe("ProgramDetailTable", () => {
  it("renders labeled rows for the program's fields", () => {
    render(<ProgramDetailTable program={detail} />);

    expect(screen.getByText("番号")).toBeInTheDocument();
    expect(screen.getByText("5")).toBeInTheDocument();
    expect(screen.getByText("Detail Program")).toBeInTheDocument();
    expect(screen.getByText("エラー")).toBeInTheDocument();
    expect(screen.getByText("7")).toBeInTheDocument();
    expect(screen.getByText("緊急警報放送").nextElementSibling).toHaveTextContent("あり");
    expect(screen.getByText("文字スーパー").nextElementSibling).toHaveTextContent("なし");
  });
});

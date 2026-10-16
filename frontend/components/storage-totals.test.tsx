import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { StorageTotals as Totals } from "@/lib/api/storage";
import { StorageTotals } from "./storage-totals";

const totals: Totals = {
  programCount: 1200,
  failedProgramCount: 0,
  droppedFrameCount: 0,
  unknownDropsProgramCount: 0,
  registeredFileCount: 3400,
  registeredBytes: 3000000000,
  originalTsBytes: 1,
  encodedBytes: 2,
  otherBytes: 3,
  movedFileBytes: 4,
  pendingFileBytes: 5,
  verifiedPhysicalBytes: null,
};

describe("StorageTotals", () => {
  it("labels each registered byte category and leaves physical usage unknown", () => {
    render(<StorageTotals totals={totals} />);
    expect(screen.getByText("総容量").nextSibling).toHaveTextContent("3,000,000,000 bytes");
    expect(screen.getByText("転送完了前の登録").nextSibling).toHaveTextContent("5 bytes");
    expect(screen.getByText("検証済みの物理使用量").nextSibling).toHaveTextContent("不明（未検証）");
  });

  it("shows program and file counts", () => {
    render(<StorageTotals totals={totals} />);
    expect(screen.getByText(/1,200 番組・3,400/)).toBeInTheDocument();
  });
});

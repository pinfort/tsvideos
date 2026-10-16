import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { DuplicateCandidatesList } from "./duplicate-candidates-list";

const kept = { id: 1, name: "保持番組", registeredBytes: 3000000000 };
const other = { id: 2, name: "整理番組", registeredBytes: 100 };

describe("DuplicateCandidatesList", () => {
  it("links the retained and removable programs with the group reason", () => {
    render(<DuplicateCandidatesList candidates={[{ reason: "OVERLAP", retained: kept, removable: [other] }]} />);
    expect(screen.getByText("録画時間の重複")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "保持番組" })).toHaveAttribute("href", "/programs/1");
    expect(screen.getByRole("link", { name: "整理番組" })).toHaveAttribute("href", "/programs/2");
    expect(screen.getByText(/登録 100 bytes/)).toBeInTheDocument();
  });

  it("labels same-broadcast groups", () => {
    render(<DuplicateCandidatesList candidates={[{ reason: "SAME_BROADCAST", retained: kept, removable: [other] }]} />);
    expect(screen.getByText("同じ放送")).toBeInTheDocument();
  });

  it("says when there are no candidates", () => {
    render(<DuplicateCandidatesList candidates={[]} />);
    expect(screen.getByText("重複の整理候補はありません。")).toBeInTheDocument();
  });
});

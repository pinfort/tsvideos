import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { SearchForm } from "./search-form";

describe("SearchForm", () => {
  it("restores all selected values and offers accessible labels", () => {
    render(
      <SearchForm
        values={{
          name: "anime",
          channel: "BS1",
          status: "ERROR",
          fileType: "TS",
          sort: "SIZE",
          direction: "ASC",
          minDrops: "0",
          maxDrops: "5",
          limit: "50",
          recordedFrom: "2026-10-01T12:00",
          recordedTo: "2026-10-02T12:00",
        }}
      />,
    );
    expect(screen.getByRole("textbox", { name: "番組名" })).toHaveValue(
      "anime",
    );
    expect(screen.getByLabelText("処理状態")).toHaveValue("ERROR");
    expect(screen.getByLabelText("利用可能な形式")).toHaveValue("TS");
    expect(screen.getByLabelText("並び順")).toHaveValue("SIZE");
    expect(screen.getByLabelText("順序")).toHaveValue("ASC");
    expect(screen.getByLabelText("録画終了（この日時を含まない）")).toHaveValue(
      "2026-10-02T12:00",
    );
  });
  it("submits every changed filter through GET and resets offset while retaining limit", () => {
    render(<SearchForm values={{ offset: "100", limit: "50", name: "old" }} />);
    fireEvent.change(screen.getByLabelText("番組名"), {
      target: { value: "new" },
    });
    fireEvent.change(screen.getByLabelText("チャンネルID"), {
      target: { value: "BS1" },
    });
    fireEvent.change(screen.getByLabelText("並び順"), {
      target: { value: "DURATION" },
    });
    const form = screen.getByRole("form", {
      name: "録画検索",
    }) as HTMLFormElement;
    expect(form).toHaveAttribute("action", "/");
    expect(form).toHaveAttribute("method", "get");
    const data = new FormData(form);
    expect(data.get("offset")).toBe("0");
    expect(data.get("limit")).toBe("50");
    expect(data.get("name")).toBe("new");
    expect(data.get("channel")).toBe("BS1");
    expect(data.get("sort")).toBe("DURATION");
  });
});

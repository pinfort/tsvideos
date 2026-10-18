import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { CreatedFile } from "@/lib/api/types";
import { VideoFilesTable } from "./video-files-table";

function makeFile(overrides: Partial<CreatedFile> = {}): CreatedFile {
  return {
    id: 1,
    splittedFileId: 1,
    file: "video.ts",
    size: 100,
    mime: null,
    encoding: null,
    status: "FILE_MOVED",
    mp4: false,
    ts: true,
    ...overrides,
  };
}

describe("VideoFilesTable", () => {
  it("shows the resolution when known and a dash otherwise", () => {
    render(
      <VideoFilesTable
        files={[
          makeFile({ id: 1, file: "a.mp4", width: 1920, height: 1080 }),
          makeFile({ id: 2, file: "b.ass", width: null, height: null }),
        ]}
      />,
    );
    const [known, unknown] = screen.getAllByRole("row");
    expect(within(known).getByText("1920×1080")).toBeInTheDocument();
    expect(within(unknown).getByText("-")).toBeInTheDocument();
  });

  it("links to the viewer when a file has an mp4 rendition", () => {
    render(<VideoFilesTable files={[makeFile({ id: 3, file: "video.mp4", mp4: true })]} />);
    expect(screen.getByText("視聴")).toHaveAttribute("href", "/video/3/view");
  });

  it("passes program metadata to the viewer for history checks", () => {
    render(
      <VideoFilesTable
        files={[makeFile({ id: 3, file: "video.mp4", mp4: true })]}
        programId={7}
        title="番組 & 特集"
      />,
    );
    const href = screen.getByText("視聴").getAttribute("href")!;
    const [path, query] = href.split("?");
    expect(path).toBe("/video/3/view");
    const params = new URLSearchParams(query);
    expect(params.get("programId")).toBe("7");
    expect(params.get("title")).toBe("番組 & 特集");
  });

  it("omits the title rather than exposing the file path", () => {
    render(
      <VideoFilesTable
        files={[makeFile({ id: 3, file: "video.mp4", mp4: true })]}
        programId={7}
      />,
    );
    const href = screen.getByText("視聴").getAttribute("href")!;
    expect(new URLSearchParams(href.split("?")[1]).has("title")).toBe(false);
  });

  it("shows a fallback message when there is no mp4 rendition", () => {
    render(<VideoFilesTable files={[makeFile({ mp4: false })]} />);
    expect(screen.getByText("動画ファイルでありません")).toBeInTheDocument();
    expect(screen.queryByText("視聴")).not.toBeInTheDocument();
  });
});

// @vitest-environment node
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getProgramDetail } from "@/lib/api/programs";
vi.mock("@/lib/api/programs", () => ({ getProgramDetail: vi.fn() }));
beforeEach(() => vi.resetAllMocks());
const request = new Request("http://localhost/api/playback/programs/1");
describe("playback metadata route", () => {
  it("validates IDs before accessing the backend", async () => {
    for (const id of ["0", "-1", "abc", "1.2", "9007199254740992"]) {
      expect(
        (await GET(request, { params: Promise.resolve({ id }) })).status,
      ).toBe(400);
    }
    expect(getProgramDetail).not.toHaveBeenCalled();
  });
  it("keeps missing and unavailable metadata distinct", async () => {
    vi.mocked(getProgramDetail).mockResolvedValue(null);
    expect(
      (await GET(request, { params: Promise.resolve({ id: "1" }) })).status,
    ).toBe(404);
    vi.mocked(getProgramDetail).mockRejectedValue(
      new Error("secret driver detail"),
    );
    const response = await GET(request, {
      params: Promise.resolve({ id: "1" }),
    });
    expect(response.status).toBe(502);
    expect(await response.text()).not.toContain("secret");
  });
  it("returns the existing metadata response", async () => {
    const detail = { program: { id: 1, name: "test", executedFileId: 1, status: "COMPLETED" as const, drops: 0, size: 100, recordedAt: "2026-10-13T00:00:00", channel: "BS1", title: "test", channelName: "BS1", duration: 60, createdFiles: [] }, videoFiles: [] };
    vi.mocked(getProgramDetail).mockResolvedValue(
      detail as Awaited<ReturnType<typeof getProgramDetail>>,
    );
    const response = await GET(request, {
      params: Promise.resolve({ id: "1" }),
    });
    expect(await response.json()).toEqual(detail);
    expect(getProgramDetail).toHaveBeenCalledWith(1);
  });
});


import { beforeEach, expect, it, vi } from "vitest";
import { ApiError } from "./client";
import {
  getOrganizationOptions,
  getProgramOrganization,
  mutateOrganization,
} from "./organization";
const { fetchJson } = vi.hoisted(() => ({ fetchJson: vi.fn() }));
vi.mock("./client", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./client")>()),
  fetchJson,
}));
beforeEach(() => {
  vi.clearAllMocks();
  fetchJson.mockResolvedValue([]);
});
it("reads named lists and persisted program membership", async () => {
  await getOrganizationOptions();
  expect(fetchJson).toHaveBeenCalledWith("/api/v1/library/collections");
  expect(fetchJson).toHaveBeenCalledWith("/api/v1/library/tags");
  await getProgramOrganization(7);
  expect(fetchJson).toHaveBeenLastCalledWith("/api/v1/programs/7/organization");
});
it("uses distinct operations for metadata and membership removal", async () => {
  await mutateOrganization({
    operation: "unassign",
    kind: "collections",
    id: 3,
    programId: 7,
  });
  expect(fetchJson).toHaveBeenLastCalledWith(
    "/api/v1/library/collections/3/programs/7",
    expect.objectContaining({ method: "DELETE" }),
  );
  await mutateOrganization({ operation: "delete", kind: "collections", id: 3 });
  expect(fetchJson).toHaveBeenLastCalledWith(
    "/api/v1/library/collections/3",
    expect.objectContaining({ method: "DELETE" }),
  );
  await mutateOrganization({ operation: "tag", programId: 7, name: "日本語" });
  expect(fetchJson).toHaveBeenLastCalledWith(
    "/api/v1/programs/7/library-tags",
    expect.objectContaining({
      method: "POST",
      body: JSON.stringify({ name: "日本語" }),
    }),
  );
});
it("returns safe duplicate and missing errors without exposing backend details", async () => {
  fetchJson.mockRejectedValue(new ApiError(409, "secret"));
  expect(
    await mutateOrganization({
      operation: "create",
      kind: "tags",
      name: "tag",
    }),
  ).toEqual({ ok: false, error: "同じ名前が既に存在します。" });
  fetchJson.mockRejectedValue(new ApiError(404, "secret"));
  expect(
    (await mutateOrganization({ operation: "delete", kind: "tags", id: 1 }))
      .error,
  ).toContain("対象が見つかりません");
});
it("rejects invalid runtime IDs before building a URL", async () => {
  const result = await mutateOrganization({
    operation: "delete",
    kind: "tags",
    id: -1,
  });
  expect(result.ok).toBe(false);
  expect(fetchJson).not.toHaveBeenCalled();
});

it("rejects invalid membership read IDs", async () => {
  await expect(getProgramOrganization(-1)).rejects.toThrow(
    "Invalid program ID",
  );
  expect(fetchJson).not.toHaveBeenCalled();
});

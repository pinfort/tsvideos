import { ApiError, fetchJson } from "./client";
import type {
  ProgramDetailResponse,
  ProgramSearchQuery,
  SearchProgramsResponse,
} from "./types";
import { programQueryParams } from "../program-query";

export async function getPrograms(
  params: ProgramSearchQuery,
): Promise<SearchProgramsResponse> {
  const search = programQueryParams(params);
  return fetchJson<SearchProgramsResponse>(
    `/api/v1/programs?${search.toString()}`,
  );
}

export async function getProgramDetail(
  id: number,
): Promise<ProgramDetailResponse | null> {
  try {
    return await fetchJson<ProgramDetailResponse>(`/api/v1/programs/${id}`);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) return null;
    throw err;
  }
}

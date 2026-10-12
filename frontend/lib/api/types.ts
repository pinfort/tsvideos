export type ProgramStatus = "REGISTERED" | "COMPLETED" | "ERROR";
export type CreatedFileStatus = "REGISTERED" | "ENCODE_SUCCESS" | "FILE_MOVED";

export interface Program {
  id: number;
  name: string;
  executedFileId: number;
  status: ProgramStatus;
  drops: number;
  size: number;
  recordedAt: string;
  channel: string;
  title: string;
  channelName: string;
  duration: number;
}

export interface CreatedFile {
  id: number;
  splittedFileId: number;
  file: string;
  size: number;
  mime: string | null;
  encoding: string | null;
  status: CreatedFileStatus;
  // Kotlin's `val isMp4/isTs: Boolean` generate isXxx() getters, and Jackson
  // strips the "is" prefix from boolean getters, so the wire keys are "mp4"/"ts".
  mp4: boolean;
  ts: boolean;
}

export interface ProgramDetail extends Program {
  createdFiles: CreatedFile[];
}

export interface SearchProgramsResponse {
  programs: Program[];
  total: number;
  limit: number;
  offset: number;
}

export interface ProgramSearchQuery {
  name?: string;
  channel?: string;
  recordedFrom?: string;
  recordedTo?: string;
  status?: ProgramStatus;
  minDrops?: number;
  maxDrops?: number;
  fileType?: "TS" | "ENCODED";
  sort?: "RECORDED_AT" | "DURATION" | "SIZE";
  direction?: "ASC" | "DESC";
  limit?: number;
  offset?: number;
}

export interface ProgramDetailResponse {
  program: ProgramDetail;
  videoFiles: CreatedFile[];
}

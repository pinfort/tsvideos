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
  // Tags attached to the recording by detectors (see lib/api/tags.ts), and the
  // detectors that have run on it - used to tell "not detected" from "not checked".
  tags: string[];
  checks: string[];
}

export interface SearchProgramsResponse {
  programs: Program[];
}

export interface ProgramDetailResponse {
  program: ProgramDetail;
  videoFiles: CreatedFile[];
}

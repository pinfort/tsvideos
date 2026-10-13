import { fetchJson } from "./client";

export interface StorageTotals {
  programCount: number;
  failedProgramCount: number;
  droppedFrameCount: number;
  unknownDropsProgramCount: number;
  registeredFileCount: number;
  registeredBytes: number;
  originalTsBytes: number;
  encodedBytes: number;
  otherBytes: number;
  movedFileBytes: number;
  pendingFileBytes: number;
  verifiedPhysicalBytes: number | null;
}
export interface StorageRecording { id: number; name: string; registeredBytes: number }
export interface StorageOverview {
  totals: StorageTotals;
  largestRecordings: StorageRecording[];
  duplicateCandidates: { reason: "SAME_BROADCAST" | "OVERLAP"; retained: StorageRecording; removable: StorageRecording[] }[];
  estimatedReclaimableBytes: number;
  excludedSharedPathCount: number;
  verifiedReclaimableBytes: number | null;
}
export const getStorageOverview = () => fetchJson<StorageOverview>("/api/v1/storage");

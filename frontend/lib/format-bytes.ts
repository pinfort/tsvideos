/** Formats registered byte counts; null means the value has not been verified. */
export function formatBytes(value: number | null): string {
  return value === null ? "不明（未検証）" : `${value.toLocaleString("ja-JP")} bytes`;
}

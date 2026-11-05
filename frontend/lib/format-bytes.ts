const UNITS = ["B", "KiB", "MiB", "GiB", "TiB", "PiB"] as const;

/**
 * Formats registered byte counts like `df -h` (base 1024, at most one fractional digit);
 * null means the value has not been verified.
 */
export function formatBytes(value: number | null): string {
  if (value === null) return "不明（未検証）";
  if (!(value >= 1024)) return `${value} B`;
  let unit = 0;
  let scaled = value;
  // Promote when rounding would show 1024 of the lower unit (e.g. 1023.96 KiB -> 1 MiB).
  while (unit < UNITS.length - 1 && Math.round(scaled * 10) / 10 >= 1024) {
    scaled /= 1024;
    unit++;
  }
  const rounded = (Math.round(scaled * 10) / 10).toFixed(1).replace(/\.0$/, "");
  return `${rounded} ${UNITS[unit]}`;
}

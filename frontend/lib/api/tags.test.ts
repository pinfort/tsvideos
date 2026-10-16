import { describe, expect, it } from "vitest";
import { detectionToJapanese, KNOWN_TAGS } from "./tags";

const [ews, superimpose] = KNOWN_TAGS;

describe("detectionToJapanese", () => {
  it("reports あり when the tag is attached", () => {
    expect(detectionToJapanese({ tags: ["ews"], checks: ["emergency_broadcast"] }, ews)).toBe("あり");
  });

  it("reports なし when the detector ran without attaching the tag", () => {
    expect(
      detectionToJapanese({ tags: ["ews"], checks: ["emergency_broadcast"] }, superimpose),
    ).toBe("なし");
  });

  it("reports 未検査 when the detector never ran", () => {
    expect(detectionToJapanese({ tags: [], checks: [] }, ews)).toBe("未検査");
  });
});

import { describe, expect, it } from "vitest";
import { detectionToJapanese, KNOWN_TAGS } from "./tags";

const [ews, superimpose, caption] = KNOWN_TAGS;

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

  it("does not infer the caption result from the emergency broadcast check", () => {
    expect(detectionToJapanese({ tags: [], checks: ["emergency_broadcast"] }, caption)).toBe(
      "未検査",
    );
  });

  it("reports the caption result from the caption check", () => {
    const checks = ["emergency_broadcast", "caption"];
    expect(detectionToJapanese({ tags: [], checks }, caption)).toBe("なし");
    expect(detectionToJapanese({ tags: ["caption"], checks }, caption)).toBe("あり");
    expect(detectionToJapanese({ tags: ["caption"], checks }, superimpose)).toBe("なし");
  });
});

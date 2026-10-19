import { expect, it } from "vitest";
import { cn } from "./utils";

it("merges conditional classes and lets callers override Tailwind defaults", () => {
  expect(
    cn("px-2 text-sm", false && "hidden", { "font-medium": true }, "px-4"),
  ).toBe("text-sm font-medium px-4");
});

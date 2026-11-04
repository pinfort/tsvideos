import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
import "@testing-library/jest-dom/vitest";

// Vitest does not overwrite globals Node already defines, so Node 25+'s own
// Web Storage would shadow jsdom's and has no backing file here. Suites using
// the node environment have no jsdom global and keep Node's globals.
const jsdomWindow = (
  globalThis as unknown as { jsdom?: { window: typeof globalThis } }
).jsdom?.window;
if (jsdomWindow) {
  for (const key of ["localStorage", "sessionStorage", "Storage"] as const) {
    Object.defineProperty(globalThis, key, {
      value: jsdomWindow[key],
      configurable: true,
      writable: true,
    });
  }
}

afterEach(() => {
  cleanup();
});

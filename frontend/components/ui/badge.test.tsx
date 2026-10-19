import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { Badge } from "./badge";

it("keeps status readable without relying on color", () => {
  render(<Badge variant="destructive">エラー</Badge>);
  expect(screen.getByText("エラー")).toBeVisible();
});

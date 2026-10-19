import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import { Input } from "./input";

it("retains native input labels, constraints and form values", async () => {
  const user = userEvent.setup();
  render(
    <form aria-label="検索">
      <label>
        件数
        <Input name="limit" type="number" min="1" max="500" required />
      </label>
    </form>,
  );
  await user.type(screen.getByLabelText("件数"), "12");
  expect(
    new FormData(screen.getByRole("form") as HTMLFormElement).get("limit"),
  ).toBe("12");
  expect(screen.getByLabelText("件数")).toBeValid();
  await user.clear(screen.getByLabelText("件数"));
  expect(screen.getByLabelText("件数")).toBeInvalid();
});

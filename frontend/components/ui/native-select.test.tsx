import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import {
  NativeSelect,
  NativeSelectOption,
  NativeSelectOptGroup,
} from "./native-select";

it("preserves native selection and submitted values", async () => {
  const user = userEvent.setup();
  render(
    <form aria-label="検索">
      <label>
        状態
        <NativeSelect name="status">
          <NativeSelectOption value="">すべて</NativeSelectOption>
          <NativeSelectOptGroup label="処理">
            <NativeSelectOption value="ERROR">エラー</NativeSelectOption>
          </NativeSelectOptGroup>
        </NativeSelect>
      </label>
    </form>,
  );
  await user.selectOptions(
    screen.getByRole("combobox", { name: "状態" }),
    "ERROR",
  );
  expect(
    new FormData(screen.getByRole("form") as HTMLFormElement).get("status"),
  ).toBe("ERROR");
});

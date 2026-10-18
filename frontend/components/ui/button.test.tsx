import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { Button } from "./button";

it("supports keyboard activation and disabled actions", async () => {
  const user = userEvent.setup();
  const onClick = vi.fn();
  const { rerender } = render(<Button onClick={onClick}>実行</Button>);
  await user.tab();
  await user.keyboard("{Enter}");
  expect(onClick).toHaveBeenCalledOnce();
  rerender(
    <Button disabled onClick={onClick}>
      実行
    </Button>,
  );
  await user.click(screen.getByRole("button"));
  expect(onClick).toHaveBeenCalledOnce();
});

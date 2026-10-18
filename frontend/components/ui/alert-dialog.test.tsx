import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import {
  AlertDialog,
  AlertDialogTrigger,
  AlertDialogContent,
  AlertDialogTitle,
  AlertDialogDescription,
  AlertDialogCancel,
  AlertDialogAction,
} from "./alert-dialog";

it("focuses cancel, traps keyboard focus and restores the trigger on Escape", async () => {
  const user = userEvent.setup();
  render(
    <AlertDialog>
      <AlertDialogTrigger>確認</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogTitle>削除の確認</AlertDialogTitle>
        <AlertDialogDescription>
          NAS のファイルを削除します。
        </AlertDialogDescription>
        <AlertDialogCancel>キャンセル</AlertDialogCancel>
        <AlertDialogAction>削除</AlertDialogAction>
      </AlertDialogContent>
    </AlertDialog>,
  );
  const trigger = screen.getByRole("button", { name: "確認" });
  await user.click(trigger);
  expect(
    screen.getByRole("alertdialog", { name: "削除の確認" }),
  ).toHaveAccessibleDescription("NAS のファイルを削除します。");
  expect(screen.getByRole("button", { name: "キャンセル" })).toHaveFocus();
  await user.tab({ shift: true });
  expect(screen.getByRole("button", { name: "削除" })).toHaveFocus();
  await user.tab();
  expect(screen.getByRole("button", { name: "キャンセル" })).toHaveFocus();
  await user.keyboard("{Escape}");
  await waitFor(() => expect(trigger).toHaveFocus());
  expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
});

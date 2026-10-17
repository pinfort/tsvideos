export type LibraryKind = "collections" | "tags";
export type LibraryLabel = { id: number; name: string; programCount: number };
export type Organization = {
  collections: LibraryLabel[];
  tags: LibraryLabel[];
};
export type OrganizationMutation =
  | { operation: "create"; kind: LibraryKind; name: string }
  | { operation: "rename"; kind: LibraryKind; id: number; name: string }
  | { operation: "delete"; kind: LibraryKind; id: number }
  | {
      operation: "assign" | "unassign";
      kind: LibraryKind;
      id: number;
      programId: number;
    }
  | { operation: "tag"; programId: number; name: string };

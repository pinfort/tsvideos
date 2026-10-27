-- Apply after 075 (#112), before deploying a build that contains it (idempotent). Upgrade processor:console together with
-- manager: the bulk apply relies on every created_file path writer claiming created_file_path_guard, which older builds
-- do not do.
-- path_key is the SHA-256 of the normalized SMB alias key of file (catalog's CreatedFilePathKey). Rows written before
-- this migration (or by SQL outside catalog) keep NULL until a bulk library preview/apply fills them in; until then they
-- are still compared, only not by index (backend/docs/bulk-library.md). Not unique: existing installations may already
-- hold aliases, which bulk operations refuse.
ALTER TABLE created_file
 ADD COLUMN IF NOT EXISTS path_key BINARY(32) NULL,
 ADD INDEX IF NOT EXISTS path_key (path_key);
-- One lock row per path_key (and per split key, CreatedFilePathKey.splitGuard), created on first use and never deleted.
-- Every catalog writer of a created_file path claims the row of the path it writes (a registration also that of its
-- split), and a bulk apply or program move claims the rows of the paths and splits it changes, with a primary-key upsert:
-- a record lock on that key only, so writers of other paths never wait.
CREATE TABLE IF NOT EXISTS created_file_path_guard (
    path_key BINARY(32) NOT NULL,
    PRIMARY KEY (path_key)
) ENGINE=InnoDB;

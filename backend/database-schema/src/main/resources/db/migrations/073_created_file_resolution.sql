-- Apply after 072. Pixel size of the encoded video; NULL for non-video artifacts and rows registered before this migration.
ALTER TABLE created_file ADD COLUMN IF NOT EXISTS width INT NULL, ADD COLUMN IF NOT EXISTS height INT NULL;

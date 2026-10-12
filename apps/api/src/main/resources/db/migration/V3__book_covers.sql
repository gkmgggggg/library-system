ALTER TABLE books ADD COLUMN cover_file varchar(64), ADD COLUMN cover_fetched_at timestamptz;
ALTER TABLE books ADD CONSTRAINT books_cover_file_format CHECK(cover_file IS NULL OR cover_file ~ '^[0-9]{10,30}\.(jpg|png|webp)$');
ALTER TABLE books ADD CONSTRAINT books_cover_pair CHECK((cover_file IS NULL) = (cover_fetched_at IS NULL));
COMMENT ON COLUMN books.cover_file IS '本地封面文件名，目录由 LIBRARY_COVER_DIR 指定；不存储临时图片签名 URL';

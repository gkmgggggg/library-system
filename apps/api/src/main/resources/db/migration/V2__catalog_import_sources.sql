CREATE TABLE catalog_import_batches (
 id uuid PRIMARY KEY,
 source text NOT NULL CHECK(source = 'fanqie'),
 input_count integer NOT NULL CHECK(input_count BETWEEN 1 AND 1000),
 inserted_count integer NOT NULL CHECK(inserted_count >= 0),
 skipped_count integer NOT NULL CHECK(skipped_count >= 0),
 created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(inserted_count + skipped_count = input_count)
);

CREATE TABLE book_sources (
 book_id uuid NOT NULL UNIQUE REFERENCES books(id),
 source text NOT NULL CHECK(source = 'fanqie'),
 external_id varchar(30) NOT NULL CHECK(external_id ~ '^[0-9]{10,30}$'),
 source_url text NOT NULL CHECK(source_url = 'https://fanqienovel.com/page/' || external_id),
 fetched_at timestamptz NOT NULL,
 import_batch_id uuid NOT NULL REFERENCES catalog_import_batches(id),
 metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK(jsonb_typeof(metadata) = 'object'),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(source, external_id)
);
CREATE INDEX book_sources_batch ON book_sources(import_batch_id);
COMMENT ON TABLE book_sources IS '公开网络小说书目来源；不代表实体馆藏或正文授权';
COMMENT ON TABLE catalog_import_batches IS 'CLI 导入批次；整批事务，重复来源跳过且不覆盖人工编辑';

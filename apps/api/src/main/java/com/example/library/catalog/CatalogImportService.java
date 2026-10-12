package com.example.library.catalog;

import com.example.library.common.Sql;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

@Service
public class CatalogImportService {
    public record Book(String externalId, String title, String author, String category,
                       String description, String sourceUrl, String fetchedAt,
                       List<String> tags, String serializationStatus) {}
    public record Document(String source, List<Book> books) {}
    public record Result(UUID batchId, int inserted, int skipped) {}
    private final Sql sql;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public CatalogImportService(Sql sql, TransactionTemplate tx, ObjectMapper json) {
        this.sql = sql; this.tx = tx; this.json = json;
    }

    public Result importDocument(Document document) {
        if (document == null || !"fanqie".equals(document.source()) || document.books() == null
                || document.books().isEmpty() || document.books().size() > 1000)
            throw new IllegalArgumentException("仅接受 1～1000 条番茄公开书目信息");
        Set<String> ids = new HashSet<>();
        for (Book book : document.books()) {
            if (book == null || book.externalId() == null || !book.externalId().matches("[0-9]{10,30}")
                    || !ids.add(book.externalId()) || !("https://fanqienovel.com/page/" + book.externalId()).equals(book.sourceUrl()))
                throw new IllegalArgumentException("来源编号重复或来源地址不合法");
            text(book.title(), 200); text(book.author(), 200); text(book.category(), 30);
            text(book.description(), 2000); text(book.serializationStatus(), 30);
            if (book.tags() == null || book.tags().size() > 30) throw new IllegalArgumentException("题材标签不合法");
            book.tags().forEach(tag -> text(tag, 100));
            Instant.parse(book.fetchedAt());
        }
        return tx.execute(status -> {
            sql.count("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('library_catalog_import_fanqie'))) t", Map.of());
            UUID batch = UUID.randomUUID(); int inserted = 0, skipped = 0;
            sql.update("INSERT INTO catalog_import_batches(id,source,input_count,inserted_count,skipped_count) VALUES(:id,'fanqie',:count,0,:count)",
                    Sql.params("id", batch, "count", document.books().size()));
            for (Book book : document.books()) {
                if (sql.count("SELECT count(*) FROM book_sources WHERE source='fanqie' AND external_id=:id", Sql.params("id", book.externalId())) > 0) {
                    skipped++; continue;
                }
                sql.update("INSERT INTO categories(name) VALUES(:name) ON CONFLICT(name_key) DO NOTHING", Sql.params("name", book.category().trim()));
                UUID category = UUID.fromString((String) sql.one("SELECT id FROM categories WHERE name_key=lower(btrim(:name))", Sql.params("name", book.category())).get("id"));
                UUID bookId = UUID.randomUUID();
                sql.update("INSERT INTO books(id,category_id,title,author,description) VALUES(:id,:category,:title,:author,:description)",
                        Sql.params("id", bookId, "category", category, "title", book.title().trim(), "author", book.author().trim(), "description", book.description()));
                String metadata = json.writeValueAsString(Map.of("tags", book.tags(), "serializationStatus", book.serializationStatus(), "descriptionKind", "metadata_overview"));
                sql.update("INSERT INTO book_sources(book_id,source,external_id,source_url,fetched_at,import_batch_id,metadata) VALUES(:book,'fanqie',:external,:url,:fetched,:batch,CAST(:metadata AS jsonb))",
                        Sql.params("book", bookId, "external", book.externalId(), "url", book.sourceUrl(), "fetched", java.sql.Timestamp.from(Instant.parse(book.fetchedAt())), "batch", batch, "metadata", metadata));
                inserted++;
            }
            sql.update("UPDATE catalog_import_batches SET inserted_count=:inserted,skipped_count=:skipped WHERE id=:id",
                    Sql.params("id", batch, "inserted", inserted, "skipped", skipped));
            return new Result(batch, inserted, skipped);
        });
    }

    private static void text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxLength
                || value.codePoints().anyMatch(code -> code >= 0xE000 && code <= 0xF8FF || code == 0))
            throw new IllegalArgumentException("书目字段为空、过长或含有网页字体编码");
    }
}

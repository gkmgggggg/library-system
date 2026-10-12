package com.example.library.catalog;

import com.example.library.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class BookCoverService {
    public record Cover(String externalId,String filename,String sha256,long size,String coverUri,String fetchedAt) {}
    public record Document(String source,List<Cover> covers,List<String> missingIds) {}
    private final Sql sql;private final CatalogService catalog;private final TransactionTemplate tx;
    private final Path directory;
    public BookCoverService(Sql sql,CatalogService catalog,TransactionTemplate tx,
        @Value("${library.cover-dir:${user.home}/Library/Application Support/library-system/covers}") String directory) {
        this.sql=sql;this.catalog=catalog;this.tx=tx;this.directory=Path.of(directory).toAbsolutePath().normalize();
    }
    public Path file(UUID id) {
        catalog.book(id);
        Object value=sql.one("SELECT cover_file FROM books WHERE id=:id",Sql.params("id",id)).get("cover_file");
        if(value==null) throw new BusinessException(404,"NO_COVER","该书暂无封面");
        Path path=safeFile(value.toString());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) throw new BusinessException(404,"NO_COVER","封面文件不存在");
        return path;
    }
    private Path safeFile(String filename) {
        if(!filename.matches("[0-9]{10,30}\\.(jpg|png|webp)")) throw new IllegalArgumentException("封面文件名不合法");
        return directory.resolve(filename);
    }
    public int importCovers(Document document) throws Exception {
        if(document==null || !"fanqie".equals(document.source()) || document.covers()==null || document.covers().size()>1000)
            throw new IllegalArgumentException("封面清单不合法");
        Set<String> ids=new HashSet<>();
        for(Cover cover:document.covers()) {
            if(cover==null || cover.externalId()==null || !cover.externalId().matches("[0-9]{10,30}") || !ids.add(cover.externalId())
                || cover.filename()==null || !cover.filename().startsWith(cover.externalId()+".") || cover.sha256()==null || !cover.sha256().matches("[0-9a-f]{64}")
                || cover.coverUri()==null || !cover.coverUri().matches("(?:novel-pic|novel-static|novel-images)/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException("封面来源或字段不合法");
            Path file=safeFile(cover.filename());
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || cover.size()<1 || cover.size()>5_000_000 || Files.size(file)!=cover.size())
                throw new IllegalArgumentException("封面文件缺失或长度不匹配");
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))).equals(cover.sha256()))
                throw new IllegalArgumentException("封面校验失败");
            Instant.parse(cover.fetchedAt());
        }
        return tx.execute(status -> {
            int updated=0;
            for(Cover cover:document.covers()) {
                var source=sql.one("SELECT book_id FROM book_sources WHERE source='fanqie' AND external_id=:id FOR UPDATE",Sql.params("id",cover.externalId()));
                UUID bookId=UUID.fromString((String)source.get("book_id"));
                updated+=sql.update("UPDATE books SET cover_file=:file,cover_fetched_at=:time WHERE id=:id AND (cover_file IS DISTINCT FROM :file OR cover_fetched_at IS DISTINCT FROM :time)",Sql.params("id",bookId,"file",cover.filename(),"time",java.sql.Timestamp.from(Instant.parse(cover.fetchedAt()))));
                sql.update("UPDATE book_sources SET metadata=metadata || jsonb_build_object('coverUri',CAST(:uri AS text),'coverSha256',CAST(:sha AS text)) WHERE book_id=:id",Sql.params("id",bookId,"uri",cover.coverUri(),"sha",cover.sha256()));
            }
            return updated;
        });
    }
}

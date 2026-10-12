package com.example.library.catalog;

import com.example.library.common.Sql;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class CatalogMaintenanceService {
    public record Result(int deletedBooks,int deletedCopies,int deletedCategories,int addedCopies,int skippedBooks) {}
    public record DistributionResult(int processedBooks,int addedCopies,int relocatedCopies,int maintenanceCopies,int skippedBooks,long totalCopies) {}
    private final Sql sql; private final TransactionTemplate tx;
    public CatalogMaintenanceService(Sql sql, TransactionTemplate tx) { this.sql=sql;this.tx=tx; }
    public Result prepareFanqieSamples() {
        return tx.execute(status -> {
            sql.count("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('library_prepare_fanqie'))) t",Map.of());
            var demo=sql.list("SELECT b.id FROM books b WHERE b.author='学习项目编写组' AND b.description='仅用于本地学习的虚构书目' AND b.title IN ('数据库入门练习','SQL 查询手册（演示）','城市里的故事（演示）','历史阅读笔记（演示）') AND NOT EXISTS(SELECT 1 FROM book_sources s WHERE s.book_id=b.id) ORDER BY b.id FOR UPDATE",Map.of());
            int deletedCopies=0,deletedBooks=0;
            for(var row:demo) {
                UUID id=UUID.fromString((String)row.get("id"));
                sql.list("SELECT id FROM book_copies WHERE book_id=:id ORDER BY id FOR UPDATE",Sql.params("id",id));
                if(sql.count("SELECT count(*) FROM loans l JOIN book_copies bc ON bc.id=l.copy_id WHERE bc.book_id=:id",Sql.params("id",id))>0)
                    throw new IllegalStateException("演示书目已有借阅历史，整个操作已回滚；请通过业务页面归档");
                deletedCopies+=sql.update("DELETE FROM book_copies WHERE book_id=:id",Sql.params("id",id));
                deletedBooks+=sql.update("DELETE FROM books WHERE id=:id",Sql.params("id",id));
            }
            int deletedCategories=sql.update("DELETE FROM categories c WHERE c.name IN ('计算机','文学','历史') AND NOT EXISTS(SELECT 1 FROM books b WHERE b.category_id=c.id)",Map.of());
            int added=0,skipped=0;
            var books=sql.list("SELECT b.id,b.archived_at FROM books b JOIN book_sources s ON s.book_id=b.id WHERE s.source='fanqie' ORDER BY b.id FOR UPDATE OF b",Map.of());
            for(var row:books) {
                UUID id=UUID.fromString((String)row.get("id"));
                if(row.get("archived_at")!=null || sql.count("SELECT count(*) FROM book_copies WHERE book_id=:id",Sql.params("id",id))>0) { skipped++;continue; }
                added+=sql.update("INSERT INTO book_copies(book_id,location,note) VALUES(:id,:location,:note)",Sql.params("id",id,"location","学习库 / 番茄小说","note","学习用模拟副本，用于借还流程练习；不代表已购入纸质实物"));
            }
            return new Result(deletedBooks,deletedCopies,deletedCategories,added,skipped);
        });
    }

    public DistributionResult distributeFanqieCopies() {
        return tx.execute(status -> {
            sql.count("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('library_prepare_fanqie'))) t",Map.of());
            var books=sql.list("SELECT b.id,b.archived_at,s.external_id,r.ordinal FROM books b JOIN book_sources s ON s.book_id=b.id JOIN (SELECT id,row_number() OVER(ORDER BY name,id) AS ordinal FROM categories) r ON r.id=b.category_id WHERE s.source='fanqie' ORDER BY b.id FOR UPDATE OF b",Map.of());
            int processed=0,added=0,relocated=0,maintenance=0,skipped=0;
            for(var book:books) {
                if(book.get("archived_at")!=null) { skipped++;continue; }
                UUID id=UUID.fromString((String)book.get("id"));
                byte[] seed=inventorySeed((String)book.get("external_id"));
                int bucket=Math.floorMod(java.nio.ByteBuffer.wrap(seed).getInt(),100);
                int target=bucket<45?1:bucket<75?2:bucket<90?3:bucket<97?4:bucket<99?5:6;
                int ordinal=((Number)book.get("ordinal")).intValue()-1;
                String shelf=String.format(Locale.ROOT,"%c 区 / %02d 架 / %02d 层",'A'+ordinal/8,ordinal%8+1,1+Math.floorMod(java.nio.ByteBuffer.wrap(seed,8,4).getInt(),3));
                var copies=sql.list("SELECT id,status,location,note FROM book_copies WHERE book_id=:id ORDER BY created_at,id FOR UPDATE",Sql.params("id",id));
                String note="学习用模拟副本（馆藏分布 v1），用于借还练习；不代表已购入纸质实物";
                // Only move the untouched copies from the initial sample. Preserve circulation and manual changes.
                relocated+=sql.update("UPDATE book_copies bc SET location=:location,note=:note WHERE bc.book_id=:id AND bc.status='available' AND bc.location='学习库 / 番茄小说' AND bc.note='学习用模拟副本，用于借还流程练习；不代表已购入纸质实物' AND NOT EXISTS(SELECT 1 FROM loans l WHERE l.copy_id=bc.id)",Sql.params("id",id,"location",shelf,"note",note));
                boolean repair=target>1 && Math.floorMod(java.nio.ByteBuffer.wrap(seed,4,4).getInt(),20)==0;
                // Count all existing copies, including retired ones; reruns must not revive withdrawn stock.
                for(int number=copies.size()+1;number<=target;number++) {
                    boolean unavailable=repair && number==target;
                    String location=number>3?String.format(Locale.ROOT,"储备区 / %02d 架 / 01 层",ordinal+1):shelf;
                    added+=sql.update("INSERT INTO book_copies(book_id,location,note,status,status_reason) VALUES(:id,:location,:note,:status,:reason)",Sql.params("id",id,"location",location,"note",note+(unavailable?"；模拟检修样本":""),"status",unavailable?"unavailable":"available","reason",unavailable?"模拟检修：书脊松动，待修复（学习样本）":null));
                    if(unavailable) maintenance++;
                }
                processed++;
            }
            long total=sql.count("SELECT count(*) FROM book_copies bc JOIN book_sources s ON s.book_id=bc.book_id WHERE s.source='fanqie'",Map.of());
            return new DistributionResult(processed,added,relocated,maintenance,skipped,total);
        });
    }

    private static byte[] inventorySeed(String externalId) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(("library-fanqie-stock-v1:"+externalId).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}

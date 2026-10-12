package com.example.library.catalog;
import com.example.library.common.*;
import com.example.library.auth.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class CatalogService {
    private final Sql sql;
    public CatalogService(Sql sql) { this.sql=sql; }
    static final String BOOK_COLUMNS="""
      b.id,b.title,b.author,b.publisher,b.isbn,b.publication_year AS "publicationYear",b.edition,b.description,
      b.category_id AS "categoryId",c.name AS category,b.archived_at AS "archivedAt",
      (SELECT count(*) FROM book_copies x WHERE x.book_id=b.id AND x.status<>'retired') AS "totalCopies",
      (SELECT count(*) FROM book_copies x WHERE x.book_id=b.id AND x.status='available') AS "availableCopies"
      """;
    public Map<String,Object> books(String q,UUID category,boolean archived,int page,int size) {
        var user=CurrentUser.get();if(archived) CurrentUser.admin();
        String filter=" WHERE "+(archived?"b.archived_at IS NOT NULL":"b.archived_at IS NULL")+" AND (b.title ILIKE :q OR b.author ILIKE :q OR coalesce(b.publisher,'') ILIKE :q OR coalesce(b.isbn,'') ILIKE :q)"+(category==null?"":" AND b.category_id=:category");
        var p=Sql.params("q",Sql.contains(q),"category",category);
        return sql.page("SELECT "+BOOK_COLUMNS+" FROM books b JOIN categories c ON c.id=b.category_id"+filter+" ORDER BY b.created_at DESC,b.id DESC","SELECT count(*) FROM books b"+filter,p,page,size);
    }
    public Map<String,Object> book(UUID id) {
        var b=sql.one("SELECT "+BOOK_COLUMNS+" FROM books b JOIN categories c ON c.id=b.category_id WHERE b.id=:id",Sql.params("id",id));
        if(b.get("archivedAt")!=null && !CurrentUser.get().role().equals("admin")) throw new BusinessException(404,"NOT_FOUND","图书不存在");
        return b;
    }
    public List<Map<String,Object>> categories() { return sql.list("SELECT c.id,c.name,(SELECT count(*) FROM books b WHERE b.category_id=c.id) AS \"bookCount\" FROM categories c ORDER BY c.name,c.id",Map.of()); }
    public Map<String,Object> category(UUID id,String name) {
        CurrentUser.admin();String trimmed=name.trim();BusinessException.check(!trimmed.isBlank(),"INVALID_NAME","分类名称不能为空");
        return id==null?sql.one("INSERT INTO categories(name) VALUES(:name) RETURNING id,name",Sql.params("name",trimmed)):
            sql.one("UPDATE categories SET name=:name WHERE id=:id RETURNING id,name",Sql.params("name",trimmed,"id",id));
    }
    public void deleteCategory(UUID id) { CurrentUser.admin();if(sql.update("DELETE FROM categories WHERE id=:id",Sql.params("id",id))==0) throw new BusinessException(404,"NOT_FOUND","分类不存在"); }
    public Map<String,Object> saveBook(UUID id,CatalogController.BookInput body) {
        CurrentUser.admin();var p=Sql.params("id",id,"title",body.title().trim(),"author",body.author().trim(),"publisher",body.publisher(),"isbn",Isbn.normalize(body.isbn()),"year",body.publicationYear(),"edition",body.edition(),"category",body.categoryId(),"description",Objects.toString(body.description(),""));
        if(id==null) return sql.one("INSERT INTO books(title,author,publisher,isbn,publication_year,edition,category_id,description) VALUES(:title,:author,:publisher,:isbn,:year,:edition,:category,:description) RETURNING id",p);
        return sql.one("UPDATE books SET title=:title,author=:author,publisher=:publisher,isbn=:isbn,publication_year=:year,edition=:edition,category_id=:category,description=:description WHERE id=:id RETURNING id",p);
    }
    @Transactional public void archive(UUID id,boolean archive) {
        CurrentUser.admin();sql.one("SELECT id FROM books WHERE id=:id FOR UPDATE",Sql.params("id",id));
        if(archive) BusinessException.check(sql.count("SELECT count(*) FROM loans l JOIN book_copies bc ON bc.id=l.copy_id WHERE bc.book_id=:id AND l.returned_at IS NULL",Sql.params("id",id))==0,"ACTIVE_LOANS","该图书仍有未归还副本，不能归档");
        sql.update("UPDATE books SET archived_at="+(archive?"now()":"NULL")+" WHERE id=:id",Sql.params("id",id));
    }
    public Map<String,Object> copies(String q,UUID bookId,int page,int size) {
        CurrentUser.admin();String where=" WHERE (bc.code ILIKE :q OR b.title ILIKE :q)"+(bookId==null?"":" AND bc.book_id=:book");
        return sql.page("SELECT bc.id,bc.code,bc.book_id AS \"bookId\",b.title,bc.location,bc.status,bc.status_reason AS \"statusReason\",bc.note FROM book_copies bc JOIN books b ON b.id=bc.book_id"+where+" ORDER BY bc.created_at DESC,bc.id DESC","SELECT count(*) FROM book_copies bc JOIN books b ON b.id=bc.book_id"+where,Sql.params("q",Sql.contains(q),"book",bookId),page,size);
    }
    @Transactional public Map<String,Object> addCopy(UUID bookId,CatalogController.CopyInput body) {
        CurrentUser.admin();var book=sql.one("SELECT archived_at FROM books WHERE id=:id FOR SHARE",Sql.params("id",bookId));
        BusinessException.check(book.get("archived_at")==null,"ARCHIVED_BOOK","请先恢复图书再添加副本");
        return sql.one("INSERT INTO book_copies(book_id,location,note) VALUES(:book,:location,:note) RETURNING id,code",Sql.params("book",bookId,"location",Objects.toString(body.location(),""),"note",Objects.toString(body.note(),"")));
    }
    @Transactional public void copyState(UUID id,CatalogController.CopyState body) {
        CurrentUser.admin();var pointer=sql.one("SELECT book_id FROM book_copies WHERE id=:id",Sql.params("id",id));
        sql.one("SELECT id FROM books WHERE id=:id FOR SHARE",Sql.params("id",UUID.fromString((String)pointer.get("book_id"))));
        var copy=sql.one("SELECT status FROM book_copies WHERE id=:id FOR UPDATE",Sql.params("id",id));
        BusinessException.check(!copy.get("status").equals("borrowed"),"ACTIVE_LOAN","已借出副本只能通过归还流程处理");
        BusinessException.check(!copy.get("status").equals("retired"),"RETIRED_COPY","已注销副本不能恢复流通");
        BusinessException.check(Set.of("available","unavailable","retired").contains(body.status()),"INVALID_STATUS","不支持该状态");
        if(!body.status().equals("available")) BusinessException.check(body.reason()!=null&&!body.reason().isBlank(),"REASON_REQUIRED","请填写不可借或注销原因");
        sql.update("UPDATE book_copies SET status=:status,status_reason=:reason,location=:location,note=:note WHERE id=:id",Sql.params("id",id,"status",body.status(),"reason",body.reason(),"location",Objects.toString(body.location(),""),"note",Objects.toString(body.note(),"")));
    }
}

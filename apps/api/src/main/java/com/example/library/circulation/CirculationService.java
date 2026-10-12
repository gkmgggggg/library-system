package com.example.library.circulation;
import com.example.library.auth.*;
import com.example.library.common.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.time.LocalDate;
import java.util.function.Supplier;

@Service
public class CirculationService {
    private final Sql sql;private final ObjectMapper json;
    public CirculationService(Sql sql,ObjectMapper json) { this.sql=sql;this.json=json; }
    @SuppressWarnings("unchecked")
    private Map<String,Object> once(CurrentUser actor,String operation,UUID key,Object request,Supplier<Map<String,Object>> work) {
        String hash=AuthService.digest(json.writeValueAsString(request));
        var p=Sql.params("actor",actor.id(),"operation",operation,"key",key,"hash",hash);
        sql.update("INSERT INTO idempotency_keys(actor_id,operation,key,request_hash) VALUES(:actor,:operation,:key,:hash) ON CONFLICT DO NOTHING",p);
        var row=sql.one("SELECT request_hash,response::text AS response FROM idempotency_keys WHERE actor_id=:actor AND operation=:operation AND key=:key FOR UPDATE",p);
        BusinessException.check(hash.equals(row.get("request_hash")),"KEY_REUSED","同一个请求键不能用于不同操作内容");
        if(row.get("response")!=null) return json.readValue((String)row.get("response"),Map.class);
        var result=work.get();p.put("response",json.writeValueAsString(result));
        sql.update("UPDATE idempotency_keys SET response=CAST(:response AS jsonb) WHERE actor_id=:actor AND operation=:operation AND key=:key",p);
        return result;
    }
    private Map<String,Object> lockReader(UUID id) {
        var pointer=sql.one("SELECT user_id FROM readers WHERE id=:id",Sql.params("id",id));
        var user=sql.one("SELECT enabled FROM users WHERE id=:id FOR NO KEY UPDATE",Sql.params("id",UUID.fromString((String)pointer.get("user_id"))));
        sql.one("SELECT id FROM readers WHERE id=:id FOR NO KEY UPDATE",Sql.params("id",id));return user;
    }
    private Map<String,Object> lockCopy(UUID id) {
        var pointer=sql.one("SELECT book_id FROM book_copies WHERE id=:id",Sql.params("id",id));
        var book=sql.one("SELECT archived_at FROM books WHERE id=:id FOR SHARE",Sql.params("id",UUID.fromString((String)pointer.get("book_id"))));
        var copy=sql.one("SELECT id,status FROM book_copies WHERE id=:id FOR UPDATE",Sql.params("id",id));copy.put("archived_at",book.get("archived_at"));return copy;
    }
    @Transactional public Map<String,Object> borrow(UUID key,CirculationController.Borrow body) {
        var actor=CurrentUser.admin();return once(actor,"borrow",key,body,()-> {
            var user=lockReader(body.readerId());var copy=lockCopy(body.copyId());
            BusinessException.check(Boolean.TRUE.equals(user.get("enabled")),"READER_DISABLED","读者账号已停用");
            BusinessException.check(copy.get("archived_at")==null,"BOOK_ARCHIVED","图书已经归档");
            BusinessException.check(copy.get("status").equals("available"),"COPY_NOT_AVAILABLE","该馆藏副本当前不可借");
            BusinessException.check(sql.count("SELECT count(*) FROM loans WHERE reader_id=:id AND returned_at IS NULL",Sql.params("id",body.readerId()))<5,"LOAN_LIMIT","每位读者最多同时借阅 5 册");
            BusinessException.check(sql.count("SELECT count(*) FROM loans WHERE reader_id=:id AND returned_at IS NULL AND due_date<library_today()",Sql.params("id",body.readerId()))==0,"OVERDUE_LOANS","读者有逾期未还图书");
            var result=sql.one("INSERT INTO loans(reader_id,copy_id,borrowed_by,original_due_date,due_date,note) VALUES(:reader,:copy,:actor,library_today()+30,library_today()+30,:note) RETURNING id,due_date AS \"dueDate\"",Sql.params("reader",body.readerId(),"copy",body.copyId(),"actor",actor.id(),"note",Objects.toString(body.note(),"")));
            sql.update("UPDATE book_copies SET status='borrowed',status_reason=NULL WHERE id=:id",Sql.params("id",body.copyId()));return result;
        });
    }
    private Map<String,Object> lockedLoan(UUID id) {
        var pointer=sql.one("SELECT reader_id,copy_id FROM loans WHERE id=:id",Sql.params("id",id));
        var user=lockReader(UUID.fromString((String)pointer.get("reader_id")));lockCopy(UUID.fromString((String)pointer.get("copy_id")));
        var row=sql.one("SELECT *,due_date<library_today() AS overdue FROM loans WHERE id=:id FOR UPDATE",Sql.params("id",id));row.put("reader_enabled",user.get("enabled"));return row;
    }
    @Transactional public Map<String,Object> giveBack(UUID key,UUID loanId,CirculationController.Return body) {
        var actor=CurrentUser.admin();return once(actor,"return",key,Map.of("id",loanId,"body",body),()-> {
            var loan=lockedLoan(loanId);BusinessException.check(loan.get("returned_at")==null,"ALREADY_RETURNED","该借阅已经归还");
            if(body.damaged()) BusinessException.check(body.note()!=null&&!body.note().isBlank(),"REASON_REQUIRED","破损归还需要填写原因");
            sql.update("UPDATE loans SET returned_at=now(),returned_by=:actor,return_note=:note WHERE id=:id",Sql.params("actor",actor.id(),"note",Objects.toString(body.note(),""),"id",loanId));
            sql.update("UPDATE book_copies SET status=:status,status_reason=:reason WHERE id=:id",Sql.params("status",body.damaged()?"unavailable":"available","reason",body.damaged()?body.note():null,"id",UUID.fromString((String)loan.get("copy_id"))));
            return Map.of("id",loanId.toString(),"ok",true);
        });
    }
    @Transactional public Map<String,Object> renew(UUID key,UUID loanId) {
        var actor=CurrentUser.get();
        // 在任何写入前检查记录归属，不把自己的账号 ID 当作任意读者查询参数。
        var owner=sql.one("SELECT r.user_id FROM loans l JOIN readers r ON r.id=l.reader_id WHERE l.id=:id",Sql.params("id",loanId));
        if(!actor.role().equals("admin")&&!actor.id().toString().equals(owner.get("user_id"))) throw new BusinessException(403,"FORBIDDEN","只能续借自己的图书");
        return once(actor,"renew",key,Map.of("id",loanId),()-> {
            var loan=lockedLoan(loanId);BusinessException.check(Boolean.TRUE.equals(loan.get("reader_enabled")),"READER_DISABLED","账号已停用");
            BusinessException.check(loan.get("returned_at")==null,"ALREADY_RETURNED","已归还借阅不能续借");
            BusinessException.check(!Boolean.TRUE.equals(loan.get("overdue")),"OVERDUE_LOAN","逾期借阅不能续借");
            UUID reader=UUID.fromString((String)loan.get("reader_id"));
            BusinessException.check(sql.count("SELECT count(*) FROM loans WHERE reader_id=:reader AND returned_at IS NULL AND due_date<library_today()",Sql.params("reader",reader))==0,"OVERDUE_LOANS","存在逾期未还图书");
            BusinessException.check(sql.count("SELECT count(*) FROM loan_renewals WHERE loan_id=:id",Sql.params("id",loanId))==0,"RENEWAL_LIMIT","该借阅已续借过一次");
            var result=sql.one("UPDATE loans SET due_date=due_date+renewal_days WHERE id=:id RETURNING id,due_date AS \"dueDate\"",Sql.params("id",loanId));
            sql.update("INSERT INTO loan_renewals(loan_id,operated_by,old_due_date,new_due_date) VALUES(:id,:actor,CAST(:old AS date),CAST(:new AS date))",Sql.params("id",loanId,"actor",actor.id(),"old",loan.get("due_date"),"new",result.get("dueDate")));return result;
        });
    }
    public Map<String,Object> list(boolean mine,String q,String state,String timing,UUID reader,LocalDate from,LocalDate to,int page,int size) {
        var actor=CurrentUser.get();if(!mine) CurrentUser.admin();
        if(!Set.of("all","active","returned").contains(state)||!Set.of("all","overdue","returnedOverdue","dueSoon").contains(timing)) throw new BusinessException(400,"INVALID_FILTER","借阅筛选条件不合法");
        if(from!=null&&to!=null&&from.isAfter(to)) throw new BusinessException(400,"INVALID_DATES","起始日期不能晚于结束日期");
        String where=" WHERE (b.title ILIKE :q OR bc.code ILIKE :q OR r.name ILIKE :q OR r.code ILIKE :q)";
        if(mine) where+=" AND r.user_id=:user";if(reader!=null) where+=" AND l.reader_id=:reader";
        if(state.equals("active")) where+=" AND l.returned_at IS NULL";if(state.equals("returned")) where+=" AND l.returned_at IS NOT NULL";
        if(timing.equals("overdue")) where+=" AND l.returned_at IS NULL AND l.due_date<library_today()";
        if(timing.equals("returnedOverdue")) where+=" AND l.returned_at IS NOT NULL AND (l.returned_at AT TIME ZONE 'Asia/Shanghai')::date>l.due_date";
        if(timing.equals("dueSoon")) where+=" AND l.returned_at IS NULL AND l.due_date BETWEEN library_today() AND library_today()+3";
        if(from!=null) where+=" AND l.borrowed_at >= (CAST(:from AS date)::timestamp AT TIME ZONE 'Asia/Shanghai')";
        if(to!=null) where+=" AND l.borrowed_at < ((CAST(:to AS date)+1)::timestamp AT TIME ZONE 'Asia/Shanghai')";
        String joins=" FROM loans l JOIN readers r ON r.id=l.reader_id JOIN book_copies bc ON bc.id=l.copy_id JOIN books b ON b.id=bc.book_id";
        String select="""
           SELECT l.id,l.reader_id AS "readerId",r.code AS "readerCode",r.name AS "readerName",b.title,bc.code AS "copyCode",
           l.borrowed_at AS "borrowedAt",l.due_date AS "dueDate",l.returned_at AS "returnedAt",
           GREATEST(coalesce((l.returned_at AT TIME ZONE 'Asia/Shanghai')::date,library_today())-l.due_date,0) AS "overdueDays",
           (SELECT count(*) FROM loan_renewals lr WHERE lr.loan_id=l.id) AS "renewalCount",l.note,l.return_note AS "returnNote"
           """;
        return sql.page(select+joins+where+" ORDER BY l.borrowed_at DESC,l.id DESC","SELECT count(*)"+joins+where,Sql.params("q",Sql.contains(q),"user",actor.id(),"reader",reader,"from",from,"to",to),page,size);
    }
}

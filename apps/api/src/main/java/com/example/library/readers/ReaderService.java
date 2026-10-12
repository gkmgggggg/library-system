package com.example.library.readers;
import com.example.library.common.*;
import com.example.library.auth.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ReaderService {
    private final Sql sql;private final PasswordEncoder passwords;private final TransactionTemplate tx;
    public ReaderService(Sql sql,PasswordEncoder passwords,TransactionTemplate tx) { this.sql=sql;this.passwords=passwords;this.tx=tx; }
    public Map<String,Object> list(String q,int page,int size) {
        CurrentUser.admin();String base=" FROM readers r JOIN users u ON u.id=r.user_id WHERE (r.name ILIKE :q OR r.code ILIKE :q OR u.username ILIKE :q)";
        return sql.page("SELECT r.id,r.code,r.name,r.phone,r.note,u.username,u.enabled,(SELECT count(*) FROM loans l WHERE l.reader_id=r.id AND l.returned_at IS NULL) AS \"activeLoans\""+base+" ORDER BY r.created_at DESC,r.id DESC","SELECT count(*)"+base,Sql.params("q",Sql.contains(q)),page,size);
    }
    public Map<String,Object> detail(UUID id) {
        CurrentUser.admin();return sql.one("SELECT r.id,r.code,r.name,r.phone,r.note,u.username,u.enabled,(SELECT count(*) FROM loans l WHERE l.reader_id=r.id AND l.returned_at IS NULL) AS \"activeLoans\",(SELECT count(*) FROM loans l WHERE l.reader_id=r.id AND l.returned_at IS NULL AND l.due_date<library_today()) AS \"overdueLoans\" FROM readers r JOIN users u ON u.id=r.user_id WHERE r.id=:id",Sql.params("id",id));
    }
    public Map<String,Object> save(UUID id,ReaderController.ReaderInput body) {
        CurrentUser.admin();String hash=id==null?passwords.encode(Objects.requireNonNull(body.password())):null;
        return tx.execute(s->{
            var p=Sql.params("name",body.name().trim(),"phone",body.phone(),"note",Objects.toString(body.note(),""));
            if(id==null) {
                var user=sql.one("INSERT INTO users(username,password_hash,role) VALUES(:name,:hash,'reader') RETURNING id",Sql.params("name",body.username(),"hash",hash));
                p.put("user",UUID.fromString((String)user.get("id")));
                return sql.one("INSERT INTO readers(user_id,name,phone,note) VALUES(:user,:name,:phone,:note) RETURNING id,code",p);
            }
            p.put("id",id);return sql.one("UPDATE readers SET name=:name,phone=:phone,note=:note WHERE id=:id RETURNING id,code",p);
        });
    }
    public void account(UUID id,Boolean enabled,String password) {
        CurrentUser.admin();String hash=password==null?null:passwords.encode(password);
        tx.executeWithoutResult(s->{
            var pointer=sql.one("SELECT user_id FROM readers WHERE id=:id",Sql.params("id",id));var user=UUID.fromString((String)pointer.get("user_id"));
            sql.one("SELECT id FROM users WHERE id=:id FOR NO KEY UPDATE",Sql.params("id",user));
            if(enabled!=null) sql.update("UPDATE users SET enabled=:enabled WHERE id=:id",Sql.params("id",user,"enabled",enabled));
            if(hash!=null) sql.update("UPDATE users SET password_hash=:hash,must_change_password=true WHERE id=:id",Sql.params("id",user,"hash",hash));
            sql.update("DELETE FROM sessions WHERE user_id=:id",Sql.params("id",user));
        });
    }
}

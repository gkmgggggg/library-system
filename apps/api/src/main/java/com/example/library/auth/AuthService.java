package com.example.library.auth;
import com.example.library.common.*;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    private final Sql sql;private final PasswordEncoder passwords;private final TransactionTemplate tx;
    private final String dummy;private final Map<String,Window> limits=new ConcurrentHashMap<>();
    private record Window(long start,int count) {}
    public AuthService(Sql sql,PasswordEncoder passwords,TransactionTemplate tx) { this.sql=sql;this.passwords=passwords;this.tx=tx;dummy=passwords.encode(UUID.randomUUID().toString()); }
    public static String digest(String token) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); } }
    public static String randomToken() { var bytes=new byte[32];new SecureRandom().nextBytes(bytes);return HexFormat.of().formatHex(bytes); }
    public record LoginResult(CurrentUser user,String token) {}
    public LoginResult login(String username,String password,String ip,String previous) {
        long now=System.currentTimeMillis(); if(limits.size()>10000) limits.entrySet().removeIf(e->now-e.getValue().start()>60000);
        Window window=limits.compute(ip,(k,w)->w==null||now-w.start()>60000?new Window(now,1):new Window(w.start(),w.count()+1));
        if(window.count()>10) throw new BusinessException(429,"RATE_LIMIT","登录请求过于频繁，请稍后重试");
        var rows=sql.list("SELECT * FROM users WHERE username_key=lower(:name)",Sql.params("name",username));
        var row=rows.isEmpty()?null:rows.getFirst();boolean valid=passwords.matches(password,row==null?dummy:(String)row.get("password_hash"));
        if(row==null || !Boolean.TRUE.equals(row.get("enabled")) || !valid) throw new BusinessException(401,"INVALID_LOGIN","用户名或密码错误");
        return tx.execute(s->{
            var locked=sql.one("SELECT * FROM users WHERE id=:id FOR NO KEY UPDATE",Sql.params("id",UUID.fromString((String)row.get("id"))));
            if(!Boolean.TRUE.equals(locked.get("enabled")) || !Objects.equals(locked.get("password_hash"),row.get("password_hash"))) throw new BusinessException(401,"INVALID_LOGIN","用户名或密码错误");
            String token=randomToken();logout(previous);
            sql.update("DELETE FROM sessions WHERE expires_at<=now()",Map.of());
            sql.update("INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(:hash,:id,now()+interval '8 hours')",Sql.params("hash",digest(token),"id",UUID.fromString((String)row.get("id"))));
            return new LoginResult(new CurrentUser(UUID.fromString((String)row.get("id")),(String)row.get("username"),(String)row.get("role"),(Boolean)row.get("must_change_password")),token);
        });
    }
    public void logout(String token) { if(token!=null) sql.update("DELETE FROM sessions WHERE token_hash=:hash",Sql.params("hash",digest(token))); }
    public void changePassword(CurrentUser user,String oldPassword,String newPassword) {
        BusinessException.check(!oldPassword.equals(newPassword),"SAME_PASSWORD","新密码应与原密码不同");
        String encoded=passwords.encode(newPassword);
        tx.executeWithoutResult(s->{
            var row=sql.one("SELECT password_hash FROM users WHERE id=:id FOR NO KEY UPDATE",Sql.params("id",user.id()));
            if(!passwords.matches(oldPassword,(String)row.get("password_hash"))) throw new BusinessException(400,"INVALID_PASSWORD","原密码错误");
            sql.update("UPDATE users SET password_hash=:hash,must_change_password=false WHERE id=:id",Sql.params("hash",encoded,"id",user.id()));
            sql.update("DELETE FROM sessions WHERE user_id=:id",Sql.params("id",user.id()));
        });
    }
}

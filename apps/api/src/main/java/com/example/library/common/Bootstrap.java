package com.example.library.common;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.*;

@Component
public class Bootstrap implements CommandLineRunner {
    private final Sql sql;private final PasswordEncoder encoder;private final TransactionTemplate tx;
    @Value("${library.initialize-admin}") boolean initialize;
    @Value("${library.seed-demo}") boolean seed;
    @Value("${RESET_ADMIN:false}") boolean reset;
    public Bootstrap(Sql sql,PasswordEncoder encoder,TransactionTemplate tx) { this.sql=sql;this.encoder=encoder;this.tx=tx; }
    @Override public void run(String... args) {
        if(initialize||reset) {
            String username=System.getenv("ADMIN_USERNAME"), password=System.getenv("ADMIN_PASSWORD");
            AdminCredentials.validate(username, password);
            String hash=encoder.encode(password);
            tx.executeWithoutResult(s->{
                sql.count("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('library_init_admin'))) t",Map.of());
                if(reset) {
                    var account=sql.one("SELECT id FROM users WHERE username_key=lower(:name) AND role='admin' FOR NO KEY UPDATE",Sql.params("name",username));
                    UUID id=UUID.fromString((String)account.get("id"));
                    sql.update("UPDATE users SET password_hash=:hash,must_change_password=false WHERE id=:id",Sql.params("id",id,"hash",hash));
                    sql.update("DELETE FROM sessions WHERE user_id=:id",Sql.params("id",id));
                } else {
                    BusinessException.check(sql.count("SELECT count(*) FROM users WHERE role='admin'",Map.of())==0,"ADMIN_EXISTS","管理员已存在");
                    sql.update("INSERT INTO users(username,password_hash,role,must_change_password) VALUES(:name,:hash,'admin',false)",Sql.params("name",username,"hash",hash));
                }
            });System.out.println(reset?"管理员密码重置完成，已有会话已撤销。":"管理员初始化完成。");
        }
        if(seed) tx.executeWithoutResult(s->{
            sql.count("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext('library_seed'))) t",Map.of());
            for(String category:List.of("计算机","文学","历史")) sql.update("INSERT INTO categories(name) VALUES(:name) ON CONFLICT DO NOTHING",Sql.params("name",category));
            for(var item:List.of(List.of("数据库入门练习","计算机"),List.of("SQL 查询手册（演示）","计算机"),List.of("城市里的故事（演示）","文学"),List.of("历史阅读笔记（演示）","历史"))) {
                if(sql.count("SELECT count(*) FROM books WHERE title=:title AND author='学习项目编写组'",Sql.params("title",item.get(0)))>0) continue;
                var book=sql.one("INSERT INTO books(title,author,category_id,description) SELECT :title,'学习项目编写组',id,'仅用于本地学习的虚构书目' FROM categories WHERE name=:category RETURNING id",Sql.params("title",item.get(0),"category",item.get(1)));
                sql.update("INSERT INTO book_copies(book_id,location) SELECT :id,'A-01' FROM generate_series(1,3)",Sql.params("id",UUID.fromString((String)book.get("id"))));
            }System.out.println("虚构书目初始化完成，未创建账号。");
        });
    }
}

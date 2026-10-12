package com.example.library.statistics;
import com.example.library.auth.CurrentUser;
import com.example.library.common.Sql;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/statistics")
public class StatisticsController {
    private final Sql sql;public StatisticsController(Sql sql) { this.sql=sql; }
    @GetMapping("/overview") Map<String,Object> overview() {
        CurrentUser.admin();return sql.one("""
          SELECT (SELECT count(*) FROM books WHERE archived_at IS NULL) AS books,
          (SELECT count(*) FROM book_copies WHERE status<>'retired') AS copies,
          (SELECT count(*) FROM book_copies WHERE status='available') AS available,
          (SELECT count(*) FROM book_copies WHERE status='borrowed') AS borrowed,
          (SELECT count(*) FROM book_copies WHERE status='unavailable') AS unavailable,
          (SELECT count(*) FROM readers r JOIN users u ON u.id=r.user_id WHERE u.enabled) AS readers,
          (SELECT count(*) FROM loans WHERE returned_at IS NULL AND due_date<library_today()) AS overdue
          """,Map.of());
    }
    @GetMapping("/trend") List<Map<String,Object>> trend() {
        CurrentUser.admin();return sql.list("""
          SELECT d::date AS date,count(l.id) AS count
          FROM generate_series(library_today()-29,library_today(),interval '1 day') d
          LEFT JOIN loans l ON (l.borrowed_at AT TIME ZONE 'Asia/Shanghai')::date=d::date
          GROUP BY d ORDER BY d
          """,Map.of());
    }
    @GetMapping("/popular-books") List<Map<String,Object>> popular() {
        CurrentUser.admin();return sql.list("""
          SELECT b.id,b.title,b.archived_at IS NOT NULL AS archived,count(*) AS count
          FROM loans l JOIN book_copies bc ON bc.id=l.copy_id JOIN books b ON b.id=bc.book_id
          WHERE l.borrowed_at >= ((library_today()-29)::timestamp AT TIME ZONE 'Asia/Shanghai')
          AND l.borrowed_at < ((library_today()+1)::timestamp AT TIME ZONE 'Asia/Shanghai')
          GROUP BY b.id ORDER BY count DESC,b.id LIMIT 10
          """,Map.of());
    }
}

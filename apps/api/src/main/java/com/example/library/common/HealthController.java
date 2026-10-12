package com.example.library.common;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController @RequestMapping("/api/v1/health")
public class HealthController {
    private final Sql sql;public HealthController(Sql sql) { this.sql=sql; }
    @GetMapping("/live") Map<String,String> live() { return Map.of("status","ok"); }
    @GetMapping("/ready") ResponseEntity<?> ready() {
        try { sql.count("SELECT count(*) FROM flyway_schema_history",Map.of());return ResponseEntity.ok(Map.of("status","ok","database","ok")); }
        catch(Exception e) { return ResponseEntity.status(503).body(Map.of("status","unavailable")); }
    }
}

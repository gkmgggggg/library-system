package com.example.library.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class CatalogImportRunner implements CommandLineRunner {
    private final CatalogImportService service;
    private final ObjectMapper json;
    @Value("${library.import-catalog:}") String path;
    public CatalogImportRunner(CatalogImportService service, ObjectMapper json) {
        this.service = service; this.json = json;
    }
    @Override public void run(String... args) throws Exception {
        if (path.isBlank()) return;
        Path file = Path.of(path);
        if (Files.size(file) > 5_000_000) throw new IllegalArgumentException("书目文件不能超过 5 MB");
        var result = service.importDocument(json.readValue(Files.readString(file), CatalogImportService.Document.class));
        System.out.printf("书目导入完成：新增 %d 本，跳过 %d 本；批次 %s%n", result.inserted(), result.skipped(), result.batchId());
    }
}

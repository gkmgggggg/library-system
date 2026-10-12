package com.example.library.catalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;

@Component
public class CatalogMaintenanceRunner implements CommandLineRunner {
    private final CatalogMaintenanceService maintenance;private final BookCoverService covers;private final ObjectMapper json;
    @Value("${library.prepare-fanqie-samples:false}") boolean prepare;
    @Value("${library.import-covers:}") String manifest;
    @Value("${library.distribute-fanqie-copies:false}") boolean distribute;
    public CatalogMaintenanceRunner(CatalogMaintenanceService maintenance,BookCoverService covers,ObjectMapper json) { this.maintenance=maintenance;this.covers=covers;this.json=json; }
    @Override public void run(String... args) throws Exception {
        if(prepare) {
            var result=maintenance.prepareFanqieSamples();
            System.out.printf("样本整理完成：删除演示书 %d 本、副本 %d 册、空分类 %d 个；新增学习副本 %d 册，跳过 %d 本%n",result.deletedBooks(),result.deletedCopies(),result.deletedCategories(),result.addedCopies(),result.skippedBooks());
        }
        if(distribute) {
            var result=maintenance.distributeFanqieCopies();
            System.out.printf("馆藏分布完成：处理 %d 本，新增 %d 册，调整原位置 %d 册；新增检修样本 %d 册，跳过归档 %d 本；来源副本共 %d 册%n",result.processedBooks(),result.addedCopies(),result.relocatedCopies(),result.maintenanceCopies(),result.skippedBooks(),result.totalCopies());
        }
        if(!manifest.isBlank()) {
            Path path=Path.of(manifest);
            if(Files.size(path)>5_000_000) throw new IllegalArgumentException("封面清单过大");
            int count=covers.importCovers(json.readValue(Files.readString(path),BookCoverService.Document.class));
            System.out.println("封面关联完成：更新 "+count+" 本");
        }
    }
}

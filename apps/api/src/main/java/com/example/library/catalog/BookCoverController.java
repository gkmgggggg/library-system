package com.example.library.catalog;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class BookCoverController {
    private final BookCoverService covers;
    public BookCoverController(BookCoverService covers) { this.covers=covers; }
    @GetMapping("/api/v1/books/{id}/cover")
    ResponseEntity<FileSystemResource> cover(@PathVariable UUID id) {
        var file=covers.file(id);String name=file.getFileName().toString();
        MediaType type=MediaType.parseMediaType(name.endsWith(".png")?"image/png":name.endsWith(".webp")?"image/webp":"image/jpeg");
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.maxAge(1,TimeUnit.DAYS).cachePrivate())
            .header("X-Content-Type-Options","nosniff").body(new FileSystemResource(file));
    }
}

package com.example.library.catalog;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@RestController @RequestMapping("/api/v1")
public class CatalogController {
    private final CatalogService service;
    public CatalogController(CatalogService service) { this.service=service; }
    public record BookInput(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=200) String author,@Size(max=200) String publisher,@Size(max=32) String isbn,@Min(1) @Max(9999) Integer publicationYear,@Size(max=200) String edition,@NotNull UUID categoryId,@Size(max=2000) String description) {}
    record Name(@NotBlank @Size(max=30) String name) {}
    public record CopyInput(@Size(max=100) String location,@Size(max=500) String note) {}
    public record CopyState(@NotBlank String status,@Size(max=500) String reason,@Size(max=100) String location,@Size(max=500) String note) {}
    @GetMapping("/books") Map<String,Object> books(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID categoryId,@RequestParam(defaultValue="false") boolean archived,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.books(q,categoryId,archived,page,pageSize); }
    @GetMapping("/books/{id}") Map<String,Object> book(@PathVariable UUID id) { return service.book(id); }
    @PostMapping("/books") Map<String,Object> create(@Valid @RequestBody BookInput b) { return service.saveBook(null,b); }
    @PatchMapping("/books/{id}") Map<String,Object> update(@PathVariable UUID id,@Valid @RequestBody BookInput b) { return service.saveBook(id,b); }
    @PostMapping("/books/{id}/archive") Map<String,Boolean> archive(@PathVariable UUID id) { service.archive(id,true);return Map.of("ok",true); }
    @PostMapping("/books/{id}/restore") Map<String,Boolean> restore(@PathVariable UUID id) { service.archive(id,false);return Map.of("ok",true); }
    @GetMapping("/categories") List<Map<String,Object>> categories() { return service.categories(); }
    @PostMapping("/categories") Map<String,Object> category(@Valid @RequestBody Name n) { return service.category(null,n.name()); }
    @PatchMapping("/categories/{id}") Map<String,Object> category(@PathVariable UUID id,@Valid @RequestBody Name n) { return service.category(id,n.name()); }
    @DeleteMapping("/categories/{id}") Map<String,Boolean> remove(@PathVariable UUID id) { service.deleteCategory(id);return Map.of("ok",true); }
    @GetMapping("/copies") Map<String,Object> copies(@RequestParam(defaultValue="") String q,@RequestParam(required=false) UUID bookId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.copies(q,bookId,page,pageSize); }
    @PostMapping("/books/{id}/copies") Map<String,Object> copy(@PathVariable UUID id,@Valid @RequestBody CopyInput b) { return service.addCopy(id,b); }
    @PatchMapping("/copies/{id}") Map<String,Boolean> state(@PathVariable UUID id,@Valid @RequestBody CopyState b) { service.copyState(id,b);return Map.of("ok",true); }
}

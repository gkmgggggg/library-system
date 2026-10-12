package com.example.library.circulation;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.LocalDate;

@RestController @RequestMapping("/api/v1")
public class CirculationController {
    private final CirculationService service;public CirculationController(CirculationService service) { this.service=service; }
    public record Borrow(@NotNull UUID readerId,@NotNull UUID copyId,@Size(max=500) String note) {}
    public record Return(boolean damaged,@Size(max=500) String note) {}
    @PostMapping("/loans") Map<String,Object> borrow(@RequestHeader("Idempotency-Key") UUID key,@Valid @RequestBody Borrow b) { return service.borrow(key,b); }
    @PostMapping("/loans/{id}/return") Map<String,Object> giveBack(@RequestHeader("Idempotency-Key") UUID key,@PathVariable UUID id,@Valid @RequestBody Return b) { return service.giveBack(key,id,b); }
    @PostMapping("/loans/{id}/renew") Map<String,Object> renew(@RequestHeader("Idempotency-Key") UUID key,@PathVariable UUID id) { return service.renew(key,id); }
    @GetMapping({"/loans","/me/loans"}) Map<String,Object> list(jakarta.servlet.http.HttpServletRequest request,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="all") String state,@RequestParam(defaultValue="all") String timing,@RequestParam(required=false) UUID readerId,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(request.getRequestURI().contains("/me/"),q,state,timing,readerId,from,to,page,pageSize); }
}

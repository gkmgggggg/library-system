package com.example.library.readers;
import com.example.library.common.BusinessException;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/readers")
public class ReaderController {
    private final ReaderService service;public ReaderController(ReaderService service) { this.service=service; }
    public record ReaderInput(@Pattern(regexp="^[A-Za-z0-9_]{3,32}$") String username,@Size(min=5,max=128) String password,@NotBlank @Size(max=50) String name,@Size(max=30) String phone,@Size(max=500) String note) {}
    record Reset(@NotBlank @Size(min=5,max=128) String password) {}
    @GetMapping Map<String,Object> list(@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(q,page,pageSize); }
    @GetMapping("/{id}") Map<String,Object> detail(@PathVariable UUID id) { return service.detail(id); }
    @PostMapping Map<String,Object> create(@Valid @RequestBody ReaderInput b) { BusinessException.check(b.username()!=null&&b.password()!=null,"REQUIRED_FIELDS","创建读者需要用户名和初始密码");return service.save(null,b); }
    @PatchMapping("/{id}") Map<String,Object> edit(@PathVariable UUID id,@Valid @RequestBody ReaderInput b) { return service.save(id,b); }
    @PostMapping("/{id}/disable") Map<String,Boolean> disable(@PathVariable UUID id) { service.account(id,false,null);return Map.of("ok",true); }
    @PostMapping("/{id}/enable") Map<String,Boolean> enable(@PathVariable UUID id) { service.account(id,true,null);return Map.of("ok",true); }
    @PostMapping("/{id}/reset-password") Map<String,Boolean> reset(@PathVariable UUID id,@Valid @RequestBody Reset b) { service.account(id,null,b.password());return Map.of("ok",true); }
}

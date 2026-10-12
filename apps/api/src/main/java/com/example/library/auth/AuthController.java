package com.example.library.auth;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.*;
import java.util.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService service;private final boolean secure;
    public AuthController(AuthService service,@Value("${library.cookie-secure}") boolean secure) { this.service=service;this.secure=secure; }
    record Login(@NotBlank @Size(max=32) String username,@NotBlank @Size(max=128) String password) {}
    record ChangePassword(@NotBlank @Size(max=128) String oldPassword,@NotBlank @Size(min=5,max=128) String newPassword) {}
    @GetMapping("/csrf") Map<String,String> csrf(CsrfToken token) { return Map.of("token",token.getToken()); }
    @GetMapping("/me") CurrentUser me() { return CurrentUser.get(); }
    @PostMapping("/login") CurrentUser login(@Valid @RequestBody Login body,HttpServletRequest req,HttpServletResponse res) {
        var login=service.login(body.username(),body.password(),req.getRemoteAddr(),cookie(req));
        res.addHeader("Set-Cookie",sessionCookie(login.token(),28800)); return login.user();
    }
    @PostMapping("/logout") Map<String,Boolean> logout(HttpServletRequest req,HttpServletResponse res) { service.logout(cookie(req));res.addHeader("Set-Cookie",sessionCookie("",0));return Map.of("ok",true); }
    @PostMapping("/password") Map<String,Boolean> password(@Valid @RequestBody ChangePassword body,HttpServletResponse res) { service.changePassword(CurrentUser.get(),body.oldPassword(),body.newPassword());res.addHeader("Set-Cookie",sessionCookie("",0));return Map.of("ok",true); }
    String sessionCookie(String token,int age) { return ResponseCookie.from("library_session",token).httpOnly(true).secure(secure).sameSite("Strict").path("/").maxAge(age).build().toString(); }
    static String cookie(HttpServletRequest req) { return Arrays.stream(req.getCookies()==null?new Cookie[0]:req.getCookies()).filter(c->c.getName().equals("library_session")).map(Cookie::getValue).findFirst().orElse(null); }
}

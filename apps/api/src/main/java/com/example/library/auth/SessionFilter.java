package com.example.library.auth;
import com.example.library.common.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

public class SessionFilter extends OncePerRequestFilter {
    private final Sql sql; private final ObjectMapper json;
    public SessionFilter(Sql sql,ObjectMapper json) { this.sql=sql;this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String rid=UUID.randomUUID().toString();req.setAttribute("requestId",rid);res.setHeader("X-Request-ID",rid);
        String token=Arrays.stream(req.getCookies()==null?new Cookie[0]:req.getCookies()).filter(c->c.getName().equals("library_session")).map(Cookie::getValue).findFirst().orElse(null);
        if(token!=null && token.matches("[0-9a-f]{64}")) {
            var rows=sql.list("SELECT u.id,u.username,u.role,u.must_change_password FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.token_hash=:hash AND s.expires_at>now() AND u.enabled=true",Sql.params("hash",AuthService.digest(token)));
            if(!rows.isEmpty()) {
                var r=rows.getFirst();var user=new CurrentUser(UUID.fromString((String)r.get("id")),(String)r.get("username"),(String)r.get("role"),(Boolean)r.get("must_change_password"));
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,List.of(new SimpleGrantedAuthority("ROLE_"+user.role()))));
                if(user.mustChangePassword() && !req.getRequestURI().startsWith("/api/v1/auth/")) {
                    res.setStatus(403);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getOutputStream(),Errors.body("PASSWORD_CHANGE_REQUIRED","请先修改初始密码",rid));return;
                }
            }
        }
        chain.doFilter(req,res);
    }
}

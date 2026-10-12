package com.example.library.auth;
import com.example.library.common.Errors;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;

public class OriginFilter extends OncePerRequestFilter {
    private final String origin;private final ObjectMapper json;
    public OriginFilter(String origin,ObjectMapper json) { this.origin=origin;this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        String supplied=req.getHeader("Origin");
        if(!Set.of("GET","HEAD","OPTIONS").contains(req.getMethod())&&supplied!=null&&!origin.equals(supplied)) {
            res.setStatus(403);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getOutputStream(),Errors.body("INVALID_ORIGIN","请求来源不受信任",Errors.id(req)));return;
        }
        chain.doFilter(req,res);
    }
}

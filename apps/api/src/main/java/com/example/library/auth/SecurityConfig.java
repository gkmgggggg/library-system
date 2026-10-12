package com.example.library.auth;
import com.example.library.common.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.*;
import org.springframework.security.crypto.password.*;
import org.springframework.security.core.userdetails.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new DelegatingPasswordEncoder("pbkdf2-v58",java.util.Map.of("pbkdf2-v58",Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8())); }
    @Bean UserDetailsService unusedDefaultUser() { return username->{ throw new UsernameNotFoundException("Only database sessions are accepted"); }; }
    @Bean @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http,Sql sql,ObjectMapper json,@Value("${library.cookie-secure}") boolean secure,@Value("${library.origin}") String origin) throws Exception {
        var csrf=new CookieCsrfTokenRepository();csrf.setCookieCustomizer(c->c.path("/").sameSite("Strict").secure(secure).httpOnly(true));
        http.sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c->c.csrfTokenRepository(csrf).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/auth/login","/api/v1/auth/csrf","/api/v1/health/**","/actuator/health").permitAll().requestMatchers("/v3/api-docs/**","/swagger-ui/**","/swagger-ui.html").hasRole("admin").anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((req,res,ex)-> { res.setStatus(401);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getOutputStream(),Errors.body("UNAUTHENTICATED","请先登录",Errors.id(req))); })
                .accessDeniedHandler((req,res,ex)-> { res.setStatus(403);res.setContentType("application/json;charset=UTF-8");json.writeValue(res.getOutputStream(),Errors.body("FORBIDDEN","无权操作或请求校验失败，请刷新页面",Errors.id(req))); }))
            .addFilterBefore(new SessionFilter(sql,json),AnonymousAuthenticationFilter.class)
            .addFilterBefore(new OriginFilter(origin,json),CsrfFilter.class)
            .formLogin(f->f.disable()).httpBasic(b->b.disable()).logout(l->l.disable());
        return http.build();
    }
}

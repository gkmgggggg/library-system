package com.example.library.auth;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import com.example.library.common.BusinessException;

public record CurrentUser(UUID id,String username,String role,boolean mustChangePassword) {
    public static CurrentUser get() {
        Object principal=SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if(!(principal instanceof CurrentUser u)) throw new BusinessException(401,"UNAUTHENTICATED","请先登录");
        return u;
    }
    public static CurrentUser admin() { var u=get(); if(!u.role.equals("admin")) throw new BusinessException(403,"FORBIDDEN","需要管理员权限"); return u; }
}

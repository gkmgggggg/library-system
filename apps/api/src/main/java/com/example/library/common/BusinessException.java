package com.example.library.common;
public class BusinessException extends RuntimeException {
    public final int status;
    public final String code;
    public BusinessException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static void check(boolean condition, String code, String message) { if (!condition) throw new BusinessException(409,code,message); }
}

package com.example.library.common;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestControllerAdvice
public class Errors {
    public static Map<String,Object> body(String code,String message,String requestId) { return Map.of("error",Map.of("code",code,"message",message,"requestId",requestId)); }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e,HttpServletRequest r) { return ResponseEntity.status(e.status).body(body(e.code,e.getMessage(),id(r))); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingRequestHeaderException.class,MissingServletRequestParameterException.class})
    ResponseEntity<?> validation(Exception e,HttpServletRequest r) {
        String message="请求参数不符合要求";
        if(e instanceof MethodArgumentNotValidException v) message=v.getBindingResult().getFieldErrors().stream().findFirst().map(f->f.getField()+": "+f.getDefaultMessage()).orElse(message);
        return ResponseEntity.badRequest().body(body("INVALID_INPUT",message,id(r)));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> conflict(Exception e,HttpServletRequest r) { return ResponseEntity.status(409).body(body("DATA_CONFLICT","数据重复、存在关联记录或状态不一致，请刷新后检查",id(r))); }
    @ExceptionHandler(CannotAcquireLockException.class)
    ResponseEntity<?> busy(Exception e,HttpServletRequest r) { return ResponseEntity.status(409).body(body("BUSY","记录正在被其他操作处理，请重试",id(r))); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e,HttpServletRequest r) {
        // 不打印异常中的 SQL 参数或数据内容。
        LoggerFactory.getLogger(Errors.class).error("request={} failureType={}",id(r),e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR","服务暂时无法完成请求",id(r)));
    }
    public static String id(HttpServletRequest r) { return Objects.toString(r.getAttribute("requestId"),"unknown"); }
}

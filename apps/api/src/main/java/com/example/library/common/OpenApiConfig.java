package com.example.library.common;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import org.springframework.context.annotation.Configuration;
@Configuration
@OpenAPIDefinition(info=@Info(title="书间 · 图书借阅 API",version="0.1.0",description="管理员和读者接口；写请求需要 CSRF，借还续借需要 Idempotency-Key"))
@SecurityScheme(name="session",type=SecuritySchemeType.APIKEY,in=SecuritySchemeIn.COOKIE,paramName="library_session")
public class OpenApiConfig {}

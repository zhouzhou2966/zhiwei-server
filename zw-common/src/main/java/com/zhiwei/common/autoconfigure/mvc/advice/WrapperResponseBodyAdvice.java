package com.zhiwei.common.autoconfigure.mvc.advice;

import com.zhiwei.common.constants.Constant;
import com.zhiwei.common.domain.R;
import com.zhiwei.common.utils.WebUtils;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

@RestControllerAdvice
public class WrapperResponseBodyAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return returnType.getParameterType() != R.class && WebUtils.isGatewayRequest();
    }

    @Override
    public Object beforeBodyWrite(
            Object body, @NonNull MethodParameter returnType, @NonNull MediaType selectedContentType,
            @NonNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
            @NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response) {
        // 接口文档不能包 R。springdoc 的 openapiJson() 返回的是 byte[]，
        // 一旦被包成 R.ok(byte[]) ，Jackson 会按默认规则把 byte[] 序列化成 base64，
        // 前端拿到的是 {"code":200,"data":"eyJvcGVuYXBp..."}，Swagger UI / Knife4j 解析不了。
        // 原来这里只挡了 springfox 时代的 /v2/api-docs，迁到 knife4j 4.x / OpenAPI3 之后
        // 路径变成 /v3/api-docs 及其子路径，判断没跟着改，于是文档一直是坏的。
        String path = request.getURI().getPath();
        if (path.startsWith("/v3/api-docs") || path.equals("/v2/api-docs")) {
            return body;
        }
        if (body == null) {
            return R.ok().requestId(MDC.get(Constant.REQUEST_ID_HEADER));
        }
        if(body instanceof R){
            return body;
        }
        return R.ok(body).requestId(MDC.get(Constant.REQUEST_ID_HEADER));
    }
}

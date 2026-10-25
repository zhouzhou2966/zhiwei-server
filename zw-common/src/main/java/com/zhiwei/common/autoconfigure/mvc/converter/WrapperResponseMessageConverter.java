package com.zhiwei.common.autoconfigure.mvc.converter;

import com.zhiwei.common.utils.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.lang.NonNull;

import java.io.IOException;
import java.util.List;

public class WrapperResponseMessageConverter implements HttpMessageConverter<Object> {

    private final MappingJackson2HttpMessageConverter delegate;

    public WrapperResponseMessageConverter(
            MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter) {
        this.delegate = mappingJackson2HttpMessageConverter;
    }

    @Override
    public boolean canRead(@NonNull Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    public boolean canWrite(@NonNull Class<?> clazz, MediaType mediaType) {
        if (!WebUtils.isGatewayRequest() || !delegate.canWrite(clazz, mediaType)) {
            return false;
        }
        // 接口文档必须放行给 ByteArrayHttpMessageConverter。
        // springdoc 的 openapiJson() 返回 byte[]，一旦被 Jackson 接手，
        // 默认行为是序列化成 base64 字符串（"eyJvcGVuYXBp..."），
        // Swagger UI / Knife4j 拿到这种东西是解析不了的。
        // 这个 bean 会被 Boot 的 HttpMessageConvertersAutoConfiguration 收进转换器链，
        // 且排在默认转换器之前，所以光改 WrapperResponseBodyAdvice 不够，这里也得挡一次。
        HttpServletRequest request = WebUtils.getRequest();
        if (request != null && request.getRequestURI().startsWith("/v3/api-docs")) {
            return false;
        }
        return true;
    }

    @Override
    @NonNull
    public List<MediaType> getSupportedMediaTypes() {
        return delegate.getSupportedMediaTypes();
    }

    @Override
    @NonNull
    public Object read(@NonNull Class<?> clazz,@NonNull HttpInputMessage inputMessage) throws IOException, HttpMessageNotReadableException {
        return delegate.read(clazz, inputMessage);
    }

    @Override
    public void write(@NonNull Object o, MediaType contentType,@NonNull HttpOutputMessage outputMessage) throws IOException, HttpMessageNotWritableException {
        delegate.write(o, contentType, outputMessage);
    }
}

package com.zhiwei.authsdk.resource.interceptors;

import com.zhiwei.auth.common.constants.JwtConstants;
import com.zhiwei.common.utils.UserContext;
import feign.RequestInterceptor;
import feign.RequestTemplate;

public class FeignRelayUserInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return;
        }
        template.header(JwtConstants.USER_HEADER, userId.toString());
    }
}

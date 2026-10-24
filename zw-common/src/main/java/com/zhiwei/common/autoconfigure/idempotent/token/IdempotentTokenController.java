package com.zhiwei.common.autoconfigure.idempotent.token;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 幂等令牌签发接口
 * <p>客户端在提交"不可重复"的业务前先调用本接口取一个令牌，
 * 提交时把令牌放进请求头（默认 {@code Idempotent-Token}）。
 * <p>该接口随 zw-common 自动装配到每个 Web 服务，网关按服务前缀路由即可访问，
 * 例如 {@code GET /prs/idempotent/token}。
 **/
@Api(tags = "幂等令牌接口")
@RestController
@RequestMapping("/idempotent")
public class IdempotentTokenController {

    private final IdempotentTokenService tokenService;

    public IdempotentTokenController(IdempotentTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @ApiOperation("申请一次性幂等令牌")
    @GetMapping("/token")
    public String issueToken() {
        return tokenService.issue();
    }
}

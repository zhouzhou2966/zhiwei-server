package com.zhiwei.auth.controller;


import com.zhiwei.api.dto.user.LoginFormDTO;
import com.zhiwei.auth.common.constants.JwtConstants;
import com.zhiwei.auth.service.IAccountService;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.utils.WebUtils;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 账户登录相关接口
 */
@RestController
@RequestMapping("/accounts")
@Tag(name = "账户管理")
@RequiredArgsConstructor
public class AccountController {

    private final IAccountService accountService;

    @Operation(summary = "登录并获取token")
    @PostMapping(value = "/login")
    public String loginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, false);
    }

    @Operation(summary = "管理端登录并获取token")
    @PostMapping(value = "/admin/login")
    public String adminLoginByPw(@RequestBody LoginFormDTO loginFormDTO) {
        return accountService.login(loginFormDTO, true);
    }

    @Operation(summary = "退出登录")
    @PostMapping(value = "/logout")
    public void logout() {
        accountService.logout();
    }

    @Operation(summary = "刷新token")
    @GetMapping(value = "/refresh")
    public String refreshToken(
            @CookieValue(value = JwtConstants.REFRESH_HEADER, required = false) String studentToken,
            @CookieValue(value = JwtConstants.ADMIN_REFRESH_HEADER, required = false) String adminToken
    ) {
        if (studentToken == null && adminToken == null) {
            throw new BadRequestException("登录超时");
        }
        String host = WebUtils.getHeader("origin");
        if (host == null) {
            throw new BadRequestException("登录超时");
        }
        // 学员端 cookie 名是 refresh，管理端是 admin-refresh。
        // 原来用 host.startsWith("www", 7) 区分，太脆：它其实是在「第 8 个字符」上做匹配，
        //   http://localhost:18082  → 第 8 个字符是 'l' → 落进管理端分支（拿不到学员 token）
        //   https://www.zhiwei.com  → 第 8 个字符是 '/' → 同样落进管理端分支（HTTPS 下 www 也失效）
        // 改成优先「哪个 cookie 真的在」，两个都在时才用 origin 兜底判断。
        String token;
        if (studentToken != null && adminToken != null) {
            token = host.contains("manage") ? adminToken : studentToken;
        } else {
            token = studentToken != null ? studentToken : adminToken;
        }
        if (token == null) {
            throw new BadRequestException("登录超时");
        }
        return accountService.refreshToken(WebUtils.cookieBuilder().decode(token));
    }
}

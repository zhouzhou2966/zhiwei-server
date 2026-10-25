package com.zhiwei.common.utils;

import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Slf4j
@Data
@Accessors(chain = true, fluent = true)
public class CookieBuilder {
    private Charset charset = StandardCharsets.UTF_8;
    private int maxAge = -1;
    private String path = "/";
    private boolean httpOnly;
    private String name;
    private String value;
    private String domain;
    private final HttpServletRequest request;
    private final HttpServletResponse response;

    public CookieBuilder(HttpServletRequest request, HttpServletResponse response) {
        this.request = request;
        this.response = response;
    }

    /**
     * 构建cookie，会对cookie值用UTF-8做URL编码，避免中文乱码
     */
    public void build(){
        if (response == null) {
            log.error("response为null，无法写入cookie");
            return;
        }
        Cookie cookie = new Cookie(name, URLEncoder.encode(value, charset));
        if(StringUtils.isNotBlank(domain)) {
            cookie.setDomain(domain);
        }else if (request != null) {
            String parentDomain = resolveParentDomain(request.getServerName());
            if (parentDomain != null) {
                cookie.setDomain(parentDomain);
            }
        }
        cookie.setHttpOnly(httpOnly);
        cookie.setMaxAge(maxAge);
        cookie.setPath(path);
        log.debug("生成cookie，编码方式:{}，【{}={}，domain:{};maxAge={};path={};httpOnly={}】",
                charset.name(), name, value, domain, maxAge, path, httpOnly);
        response.addCookie(cookie);
    }

    /** IPv4 字面量，例如 192.168.150.101 */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /**
     * 从请求的 Host 推导 cookie 的父域，无法推导时返回 null（表示不设置 domain）。
     *
     * <p>只有形如 {@code www.zhiwei.com} 这种「至少三段」的域名才提取父域
     * （得到 {@code zhiwei.com}），用于子域之间共享 cookie。
     * IP 字面量、IPv6、单段主机名（localhost）、两段域名（zhiwei.com）一律返回 null，
     * 让 cookie 自动绑定当前 host。</p>
     *
     * <p><b>为什么必须做这个区分</b>：原实现无条件执行
     * {@code subAfter(serverName, ".")} 再拼 {@code "."}，对 {@code www.zhiwei.com}
     * 是对的，但对 {@code 127.0.0.1} 会得到 {@code .0.0.1}、对 {@code localhost}
     * 会得到 {@code .ocalhost}。Tomcat 9 的 {@code LegacyCookieProcessor}
     * 不校验域、静默容忍这种值，所以能跑；Tomcat 10.1 已删除该类，
     * 只剩严格的 {@code Rfc6265CookieProcessor}，直接抛
     * {@code IllegalArgumentException: An invalid domain [...] was specified for this cookie}。</p>
     *
     * <p><b>为什么返回值不带前导点</b>：RFC 6265 §4.1.2.3 规定 Domain 属性开头的 {@code "."}
     * 会被忽略，写不写等价。但 {@code Rfc6265CookieProcessor.validateDomain()} 的校验是
     * 「每个 label 必须以字母或数字开头」，首字符为 {@code "."} 时 {@code prev == -1 && cur == '.'}
     * 直接判非法 —— 所以拼成 {@code .zhiwei.com} 照样抛异常。这里只返回 {@code zhiwei.com}。</p>
     *
     * <p>现象是登录接口返回 500，但 token 其实已经签发成功 —— 挂在最后写 refresh-token
     * cookie 这一步。用 IP / localhost 访问不复现，用 {@code api.xxx.com} 这种域名访问必现。</p>
     */
    private static String resolveParentDomain(String serverName) {
        if (StringUtils.isBlank(serverName)) {
            return null;
        }
        // IP 字面量（IPv4 / IPv6）不设 domain
        if (IPV4.matcher(serverName).matches() || serverName.indexOf(':') >= 0) {
            return null;
        }
        int first = serverName.indexOf('.');
        int last = serverName.lastIndexOf('.');
        // 无点（localhost）、首字符是点、只有一个点（zhiwei.com）都提不出有意义的父域
        if (first <= 0 || first == last) {
            return null;
        }
        return serverName.substring(first + 1);
    }

    /**
     * 利用UTF-8对cookie值解码，避免中文乱码问题
     * @param cookieValue cookie原始值
     * @return 解码后的值
     */
    public String decode(String cookieValue){
        return URLDecoder.decode(cookieValue, charset);
    }
}

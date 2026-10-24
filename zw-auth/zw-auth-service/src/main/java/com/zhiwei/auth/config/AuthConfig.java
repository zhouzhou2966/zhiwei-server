package com.zhiwei.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.bootstrap.encrypt.KeyProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.security.rsa.crypto.KeyStoreKeyFactory;
import org.springframework.util.StringUtils;

import java.security.KeyPair;
import java.util.Base64;

@Configuration
@EnableConfigurationProperties(KeyProperties.class)
public class AuthConfig {

    @Bean
    @ConfigurationProperties(prefix = "encrypt")
    public KeyProperties keyProperties(){
        return new KeyProperties();
    }

    /**
     * JWT 签名密钥对。
     *
     * <p>密钥库本身也从 Nacos 下发（{@code zw.auth.jks-base64}，base64 编码的 JKS 字节），
     * 不再打进 jar：本仓库已删除 zw.jks（凭据不入库红线），若仍依赖
     * {@code classpath:zw.jks}，Jenkins 从 Gogs 拉代码构建出的 jar 里没有这个文件，
     * 重建 auth 容器时会因加载不到密钥库直接启动失败。
     *
     * <p>配了 base64 就用 {@link ByteArrayResource} 包解码后的字节 ——
     * {@link KeyStoreKeyFactory} 吃的是 {@link Resource}，不需要落临时文件；
     * 没配（例如本地想临时放一个 classpath 文件调试）则回退到 {@code encrypt.key-store.location}。
     * 与 Nacos 里已下发的 password/secret 组合后，签名密钥与迁移前 bit 级一致，已签发的 token 全部兼容。
     *
     * <p><b>这里的 {@code @Value} 是刻意的，不要改成 {@code @ConfigurationProperties}。</b>
     * 本项目其余配置都走 {@code @ConfigurationProperties}（能随 Nacos 热更新），但这个 bean 不行：
     * 它在启动时把 base64 解成 {@code KeyPair} 存下来，字段是<b>算出来的</b>而不是读出来的，
     * 重新绑定配置也不会重建密钥对。真要做成热更新，结果是「改了 Nacos 看起来没反应」；
     * 而如果靠 {@code @RefreshScope} 让整个 bean 重建，会让签名密钥在运行中被换掉，
     * 已签发的 token 全部立即失效。换密钥属于发布级操作，重启才生效是正确行为。
     */
    @Bean
    public KeyPair keyPair(KeyProperties keyProperties,
                           @Value("${zw.auth.jks-base64:}") String jksBase64) {
        Resource location;
        if (StringUtils.hasText(jksBase64)) {
            // base64 粘贴进 Nacos 时可能带换行，解码前先去掉，否则 JDK 的 Base64 解码器直接抛异常
            location = new ByteArrayResource(
                    Base64.getDecoder().decode(jksBase64.replaceAll("\\s", "")));
        } else {
            location = keyProperties.getKeyStore().getLocation();
        }
        KeyStoreKeyFactory keyStoreKeyFactory =
                new KeyStoreKeyFactory(location,
                        keyProperties.getKeyStore().getPassword().toCharArray());
        return keyStoreKeyFactory.getKeyPair(
                keyProperties.getKeyStore().getAlias(),
                keyProperties.getKeyStore().getSecret().toCharArray());
    }

    // 原来这里注册了一个 TomcatContextCustomizer，把 CookieProcessor 换成
    // org.apache.tomcat.util.http.LegacyCookieProcessor。Tomcat 10.1 已经删掉这个类
    // （只保留 Rfc6265CookieProcessor），而 Boot 3.5 内嵌的正是 Tomcat 10.1，所以这个 bean 无法保留。
    // 对当前代码没有影响：唯一写 Cookie 的地方是 CookieBuilder，它写入前先做 URLEncoder 编码，
    // 本来就不依赖 LegacyCookieProcessor 放宽的字符集。默认的 Rfc6265CookieProcessor 足够。
}

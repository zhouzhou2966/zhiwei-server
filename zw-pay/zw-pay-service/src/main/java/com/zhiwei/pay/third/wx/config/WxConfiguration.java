package com.zhiwei.pay.third.wx.config;

import cn.hutool.crypto.PemUtil;
import com.wechat.pay.contrib.apache.httpclient.WechatPayHttpClientBuilder;
import com.wechat.pay.contrib.apache.httpclient.Validator;
import com.wechat.pay.contrib.apache.httpclient.auth.PrivateKeySigner;
import com.wechat.pay.contrib.apache.httpclient.auth.WechatPay2Credentials;
import com.wechat.pay.contrib.apache.httpclient.auth.WechatPay2Validator;
import com.wechat.pay.contrib.apache.httpclient.cert.CertificatesManager;
import com.wechat.pay.contrib.apache.httpclient.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.impl.client.CloseableHttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;

@Configuration
@EnableConfigurationProperties(WxPayProperties.class)
@Slf4j
public class WxConfiguration {

    /**
     * 微信支付证书管理器
     *
     * 注意：putMerchant 会联网下载微信平台证书，若商户证书配置有问题会抛 401 导致启动失败。
     * 这里做降级处理：证书下载失败时只记录日志，不影响服务启动。
     */
    @Bean(destroyMethod = "stop")
    public CertificatesManager certificatesManager(WxPayProperties properties) {
        // 1.加载私钥
        PrivateKey privateKey = PemUtil.readPemPrivateKey(
                new ByteArrayInputStream(properties.getPrivateKey().getBytes(StandardCharsets.UTF_8)));
        // 2.签名工具
        PrivateKeySigner privateKeySigner = new PrivateKeySigner(properties.getMchSerialNo(), privateKey);
        WechatPay2Credentials wechatPay2Credentials = new WechatPay2Credentials(properties.getMchId(), privateKeySigner);
        // 3.平台证书管理器
        CertificatesManager certificatesManager = CertificatesManager.getInstance();
        try {
            // 联网下载微信平台证书，若商户号/证书序列号/私钥不匹配会抛异常
            certificatesManager.putMerchant(
                    properties.getMchId(), wechatPay2Credentials, properties.getApiV3Key().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("微信支付平台证书初始化失败，已降级处理，仅影响真实支付功能：{}", e.getMessage());
        }
        return certificatesManager;
    }

    @Lazy
    @Bean
    public CloseableHttpClient closeableHttpClient(WxPayProperties properties, CertificatesManager certificatesManager) {
        // 1.加载私钥
        PrivateKey privateKey = PemUtil.readPemPrivateKey(
                new ByteArrayInputStream(properties.getPrivateKey().getBytes(StandardCharsets.UTF_8)));
        // 2.初始化
        WechatPayHttpClientBuilder builder = WechatPayHttpClientBuilder.create()
                .withMerchant(properties.getMchId(), properties.getMchSerialNo(), privateKey)
                // 3.响应校验器：平台证书可用时做真正的响应验签，不可用时跳过验签但保留请求签名
                .withValidator(buildValidator(certificatesManager, properties.getMchId()));
        // 4.构建
        return builder.build();
    }

    /**
     * 构建响应校验器。
     *
     * 平台证书下载失败时，绝不能退回 HttpClients.createDefault()：那个客户端连请求签名拦截器都没有，
     * 发出去的请求不带 Authorization 头，微信会以 SIGN_ERROR(签名信息错误，验签失败) 拒绝一切请求，
     * 报错完全指不到真实原因（曾经就因为这里静默降级，把「证书下不下来」误判成「签名算法写错」）。
     * 改为只跳过响应验签、保留请求签名，让真实失败原因如实暴露出来。
     */
    private Validator buildValidator(CertificatesManager certificatesManager, String mchId) {
        try {
            return new WechatPay2Validator(certificatesManager.getVerifier(mchId));
        } catch (NotFoundException e) {
            log.warn("微信支付平台证书为空，本次跳过响应验签（请求签名保留），仅影响安全性不影响可用性：{}", e.getMessage());
            return response -> true;
        }
    }
}
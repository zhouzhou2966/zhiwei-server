package com.zhiwei.pay.third.wx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zhiwei.common.exceptions.BadRequestException;
import com.zhiwei.common.exceptions.CommonException;
import com.zhiwei.pay.sdk.constants.PayConstants;
import com.zhiwei.pay.third.CommonPayProperties;
import com.zhiwei.pay.third.wx.config.WxPayProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
@Component
public class WxPayClient {
    private final CloseableHttpClient wxPayClient;
    private final ObjectMapper objectMapper;
    private final CommonPayProperties payProperties;
    private final String appId;
    private final String mchId;

    public WxPayClient(
            @Lazy CloseableHttpClient wxPayClient, WxPayProperties properties,
            ObjectMapper objectMapper, CommonPayProperties commonPayProperties) {
        this.wxPayClient = wxPayClient;
        this.objectMapper = objectMapper;
        this.appId = properties.getAppId();
        this.mchId = properties.getMchId();
        this.payProperties = commonPayProperties;
    }

    public String doPostJson(String requestPath, ObjectNode body) {
        try {
            // 2.准备请求
            HttpPost httpPost = new HttpPost(requestPath);
            httpPost.addHeader("Content-type", MediaType.APPLICATION_JSON_UTF8_VALUE);
            httpPost.setEntity(new StringEntity(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            // 3.发送请求
            return execute(httpPost);
        } catch (JsonProcessingException e) {
            throw new BadRequestException(0, "请求参数格式有误", e);
        } catch (IOException e) {
            log.error("微信支付请求处理异常，请求路径：{}", requestPath);
            throw new CommonException("微信支付请求处理异常", e);
        }
    }

    public String doGetJson(String requestPath, boolean withMch, NameValuePair... params) {
        try {
            // 1.构建请求路径
            URIBuilder uriBuilder = new URIBuilder(requestPath);
            // 2.构建请求参数
            if(withMch) {
                uriBuilder.addParameter("mchid", mchId);
            }
            if (params != null && params.length > 0) {
                uriBuilder.addParameters(Arrays.asList(params.clone()));
            }
            // 3.发送请求
            return execute(new HttpGet(uriBuilder.build()));
        } catch (URISyntaxException e) {
            log.error("微信支付请求路径异常，请求路径：{}", requestPath);
            throw new CommonException("微信支付请求路径异常", e);
        } catch (IOException e) {
            log.error("微信支付请求处理异常，请求路径：{}", requestPath);
            throw new CommonException("微信支付请求处理异常", e);
        }
    }

    /**
     * 统一执行并读取响应。
     *
     * ① 公共头统一在这里设置：Accept / User-Agent 缺一不可，微信会直接回
     *    INVALID_REQUEST(Http头缺少Accept或User-Agent)，而且 POST 和 GET 的头要求本来就一致；
     * ② 以前只管 toString 返回体、不看状态码：微信返回 401 时，
     *    {"code":"SIGN_ERROR","message":"..."} 会被当成正常业务响应往上传，
     *    调用方只能看到「验签失败」，完全看不出是鉴权/证书层面的失败；
     * ③ try-with-resources 关闭响应，避免连接池里的连接被占着不还。
     */
    private String execute(HttpUriRequest request) throws IOException {
        request.setHeader("Accept", MediaType.APPLICATION_JSON_VALUE);
        request.setHeader("User-Agent", "zw-pay/1.0");
        try (CloseableHttpResponse response = wxPayClient.execute(request)) {
            int status = response.getStatusLine().getStatusCode();
            String body = response.getEntity() == null ? ""
                    : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            if (status < 200 || status >= 300) {
                log.error("微信支付请求失败，路径：{}，状态码：{}，响应：{}", request.getURI(), status, body);
            }
            return body;
        }
    }

    public ObjectNode baseParam(boolean withApp, boolean withMch, Boolean isRefund) {
        ObjectNode objectNode = objectMapper.createObjectNode();
        if(withApp){
            objectNode.put("appid", appId);
        }
        if(withMch) {
                objectNode.put("mchid", mchId);
        }
        if(isRefund != null) {
            String notifyPath = isRefund ? "/notify/refund/" : "/notify/";
            objectNode.put("notify_url",
                    payProperties.getNotifyHost() + notifyPath + PayConstants.WX_CHANNEL_CODE);
        }
        return objectNode;
    }
}
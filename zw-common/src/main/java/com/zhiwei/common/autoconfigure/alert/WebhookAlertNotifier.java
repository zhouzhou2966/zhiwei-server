package com.zhiwei.common.autoconfigure.alert;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.zhiwei.common.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Webhook 群机器人告警通知器
 * <p>
 * 为什么需要它：链路的告警原本只有日志关键字（[MQ-ERROR-ALERT]、[COUPON-DRIFT]），
 * 没人盯着日志文件，出事时等于没有告警。群机器人 webhook 是成本最低的
 * 「可感知」手段 —— 不引入任何三方依赖，一个 HTTP POST 就把消息推到人眼前。
 * </p>
 * <p>
 * 四个关键设计：
 * 1. <b>按业务码判定成功</b>：钉钉/企微在「关键词不匹配、token 失效」时返回的是
 *    HTTP 200 + {@code errcode:310000}，只看 HTTP 状态码会把失败当成功 ——
 *    这是最危险的情形（以为有告警，其实全丢了）。所以必须解析响应体里的业务码。
 * 2. <b>成功才占用节流窗口</b>：如果发送失败也把节流时间戳写进去，一次配置错误
 *    会导致接下来 5 分钟同类告警全被吞掉，问题被掩盖得更久。
 * 3. <b>失败分类 + 克制重试</b>：只有网络异常 / 5xx 这类瞬时故障重试一次；
 *    业务错误（关键词、token、参数）重试多少次都一样。钉钉限制每机器人每分钟 20 条，
 *    重试必须克制，否则会被限流 10 分钟。
 * 4. <b>fail-soft</b>：任何异常只记日志，绝不上抛 —— 告警是旁路能力，
 *    不能因为发不出去影响 error 队列消费或对账任务。
 * </p>
 * <p>
 * 刻意<b>没有</b>做的事（避免过度设计，符合当前项目体量）：
 * <ul>
 *   <li>不上异步线程池：告警被 5 分钟节流限制，单次最坏 2 秒；引入线程池要处理
 *       队列满、关闭时序、消息丢失，复杂度不划算。真到多实例高频告警时再改。</li>
 *   <li>不做分布式节流（Redis）：当前 promotion 单实例，进程内计数足够；
 *       多实例时最坏是 N 倍消息，可接受。</li>
 *   <li>不做域名白名单 / SSRF 防护：会挡住内网自建 webhook（如自建告警平台），
 *       对项目是限制；真实风险（配置被篡改）应由 Nacos 鉴权解决。</li>
 *   <li>不做心跳与恢复通知：需要额外状态机，学习项目每天一条「一切正常」没有价值。</li>
 * </ul>
 * </p>
 */
@Slf4j
public class WebhookAlertNotifier {

    private final AlertProperties properties;

    /**
     * 记录每个告警 key 上次<b>成功</b>发送的时间戳（毫秒），用于节流。
     * ※ key 是固定的小集合（mq-error / coupon-drift 等业务枚举），不需要淘汰策略
     */
    private final ConcurrentHashMap<String, Long> lastSentAt = new ConcurrentHashMap<>();

    /**
     * 启动时校验一次地址合法性：配置错了要早点在启动日志里喊出来，
     * 而不是等到出事时才发现告警发不出去
     */
    private final boolean usable;

    public WebhookAlertNotifier(AlertProperties properties) {
        this.properties = properties;
        String url = properties.getWebhookUrl();
        if (url == null || url.isBlank()) {
            this.usable = false;
            return;
        }
        this.usable = validUrl(url);
        if (!this.usable) {
            log.error("[WEBHOOK-ALERT] zw.alert.webhook-url 不是合法的 http(s) 地址，告警已禁用：{}", maskUrl(url));
        }
    }

    /**
     * 发送一条告警
     *
     * @param throttleKey 节流 key，同一 key 在节流窗口内只发第一条（如 "mq-error"、"coupon-drift"）
     * @param content     告警正文，纯文本
     */
    public void notify(String throttleKey, String content) {
        // 未配置 url（开发环境常态）或地址非法：静默跳过，业务侧无需关心
        if (!usable) {
            log.debug("[WEBHOOK-ALERT] 告警未启用，跳过：key={}, content={}", throttleKey, content);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSentAt.get(throttleKey);
        if (last != null && now - last < properties.getThrottleSeconds() * 1000) {
            log.debug("[WEBHOOK-ALERT] 告警 [{}] 处于节流窗口内，合并本次：{}", throttleKey, content);
            return;
        }
        try {
            SendResult result = sendWithRetry(content);
            if (result.success) {
                // ★ 只有真正送达才占用节流窗口：失败不写时间戳，
                //   否则一次网络抖动就会让接下来 5 分钟的同类告警全部被吞
                lastSentAt.put(throttleKey, now);
                log.info("[WEBHOOK-ALERT] 告警已推送：key={}, content={}", throttleKey, content);
            } else {
                log.error("[WEBHOOK-ALERT] 告警发送失败（不影响业务）：key={}, httpCode={}, bizCode={}, msg={}",
                        throttleKey, result.httpCode, result.bizCode, result.message);
            }
        } catch (Exception e) {
            // 告警是旁路能力，任何异常只记日志，绝不上抛影响主流程
            log.error("[WEBHOOK-ALERT] 告警发送异常（不影响业务）：key={}", throttleKey, e);
        }
    }

    /**
     * 发送并按分类重试：只有瞬时故障才重试，业务错误直接放弃
     */
    private SendResult sendWithRetry(String content) {
        String body = buildBody(content);
        String url = properties.getWebhookUrl();
        SendResult result = doPost(url, body);
        int retried = 0;
        while (!result.success && result.retryable && retried < properties.getMaxRetry()) {
            retried++;
            try {
                Thread.sleep(properties.getRetryIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            log.warn("[WEBHOOK-ALERT] 第 {} 次重试，上次原因：httpCode={}, msg={}",
                    retried, result.httpCode, result.message);
            result = doPost(url, body);
        }
        return result;
    }

    /**
     * 按机器人类型构造 text 消息体。
     * 钉钉与企业微信的 text 格式恰好相同：{"msgtype":"text","text":{"content":"..."}}
     * 飞书是另一种：{"msg_type":"text","content":{"text":"..."}}
     * 用 hutool 序列化而不是手工拼 JSON，避免正文里的引号/换行破坏结构。
     */
    private String buildBody(String content) {
        String type = properties.getWebhookType() == null ? "dingtalk" : properties.getWebhookType();
        Map<String, Object> body;
        if ("feishu".equalsIgnoreCase(type)) {
            body = Map.of("msg_type", "text", "content", Map.of("text", content));
        } else {
            // dingtalk 与 wecom 同格式，默认走这里
            body = Map.of("msgtype", "text", "text", Map.of("content", content));
        }
        return JsonUtils.toJsonStr(body);
    }

    /**
     * 用 JDK 自带的 HttpURLConnection 发 POST，不引入 RestTemplate 依赖，
     * 保证这个组件在任何引了 zw-common 的服务里都零成本可用。
     * 超时收紧到秒级：告警宁可不发，不能拖慢消费线程。
     */
    private SendResult doPost(String url, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(properties.getConnectTimeoutMs());
            conn.setReadTimeout(properties.getReadTimeoutMs());
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = "";
            if (is != null) {
                try (is) {
                    resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return parse(code, resp);
        } catch (Exception e) {
            // 连接失败 / 超时 / 读写异常：都属于瞬时故障，可以重试一次
            return new SendResult(0, null, e.getClass().getSimpleName() + ": " + e.getMessage(), false, true);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 解析响应：HTTP 2xx 只代表「请求到达了」，机器人是否真的把消息发出去看业务码。
     * 钉钉/企微是 {@code errcode}，飞书新版是 {@code code}、老版是 {@code StatusCode}，0 为成功。
     * <p>
     * 这是本类最关键的一段：不解析业务码就会出现「日志说已推送、群里一条没有」的假阳性。
     * </p>
     */
    private SendResult parse(int httpCode, String resp) {
        if (httpCode < 200 || httpCode >= 300) {
            // 5xx / 429 视为瞬时故障可重试；4xx 是配置或权限问题，重试无意义
            boolean retryable = httpCode >= 500 || httpCode == 429;
            return new SendResult(httpCode, null, "HTTP " + httpCode + " " + truncate(resp), false, retryable);
        }
        try {
            JSONObject json = JSONUtil.parseObj(resp);
            Integer bizCode = null;
            if (json.containsKey("errcode")) {
                bizCode = json.getInt("errcode");
            } else if (json.containsKey("code")) {
                bizCode = json.getInt("code");
            } else if (json.containsKey("StatusCode")) {
                bizCode = json.getInt("StatusCode");
            }
            if (bizCode == null) {
                // 不认识的响应格式（比如自建 webhook 只回 200 空体）：按成功处理，但要留痕
                log.warn("[WEBHOOK-ALERT] 无法识别的响应格式，按成功处理：{}", truncate(resp));
                return new SendResult(httpCode, null, truncate(resp), true, false);
            }
            if (bizCode == 0) {
                return new SendResult(httpCode, 0, "ok", true, false);
            }
            // 业务错误一律不重试：关键词不匹配(310000)、token 失效、参数错、限流(130101，限流 10 分钟)
            String msg = json.getStr("errmsg", json.getStr("msg", json.getStr("StatusMessage", "")));
            return new SendResult(httpCode, bizCode, msg, false, false);
        } catch (Exception e) {
            log.warn("[WEBHOOK-ALERT] 响应解析失败，按成功处理：{}", truncate(resp), e);
            return new SendResult(httpCode, null, truncate(resp), true, false);
        }
    }

    private boolean validUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 日志里隐藏 access_token：出问题排查时要看到地址，但凭据不能进日志
     */
    private String maskUrl(String url) {
        return url.replaceAll("(access_token|key|token)=[^&]*", "$1=***");
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    /**
     * 一次发送的结果
     * <p>success 看业务码；retryable 只对瞬时故障为 true，避免对配置错误做无意义重试</p>
     */
    private static final class SendResult {
        private final int httpCode;
        private final Integer bizCode;
        private final String message;
        private final boolean success;
        private final boolean retryable;

        private SendResult(int httpCode, Integer bizCode, String message, boolean success, boolean retryable) {
            this.httpCode = httpCode;
            this.bizCode = bizCode;
            this.message = message;
            this.success = success;
            this.retryable = retryable;
        }
    }
}

package com.zhiwei.common.autoconfigure.sentinel;

import com.alibaba.csp.sentinel.datasource.Converter;
import com.alibaba.csp.sentinel.datasource.ReadableDataSource;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.property.SentinelProperty;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.param.ParamFlowRuleManager;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.function.Consumer;

/**
 * Sentinel 规则持久化到 Nacos
 *
 * <p><b>为什么必须做这一步</b>：
 * 只引入 {@code spring-cloud-starter-alibaba-sentinel} 时，规则默认只存在于<b>应用内存</b>中。
 * 通过控制台推下来的规则，一旦服务重启就全部丢失 —— 等于每次发版都要人工重新推一遍规则；
 * 更麻烦的是滚动发布期间不同实例的规则会不一致（老实例有规则、新实例没规则），限流形同虚设。
 * 把规则落到 Nacos 配置中心后，服务启动即自动拉取，规则变更还能实时推送（Nacos 长轮询），
 * 这才是"限流真正能在生产用"的前提。
 *
 * <p>约定的 Nacos 配置（group 固定 {@code SENTINEL_GROUP}）：
 * <ul>
 *     <li>{@code {应用名}-flow-rules} —— 流控规则，JSON 数组</li>
 *     <li>{@code {应用名}-degrade-rules} —— 熔断降级规则，JSON 数组</li>
 *     <li>{@code {应用名}-param-flow-rules} —— 热点参数限流规则，JSON 数组</li>
 * </ul>
 * 配置不存在时 Sentinel 只会打一条警告，不影响服务启动。
 *
 * <p>这里用 Jackson 而不是 Sentinel 文档示例里的 fastjson 来解析规则：
 * {@code sentinel-datasource-nacos} 把 fastjson 声明成了可选依赖，
 * 直接照抄示例会在没显式引入 fastjson 的工程里编译不过；
 * 而项目本身已经具备 Jackson，没必要为了解析一段规则 JSON 再引入一个 JSON 库。
 **/
@Slf4j
@Configuration
@ConditionalOnClass({NacosDataSource.class, FlowRuleManager.class})
@ConditionalOnProperty(prefix = "zw.sentinel", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SentinelRuleConfig {

    /**
     * 规则在 Nacos 中的分组，与 Sentinel 控制台的默认约定保持一致
     */
    private static final String GROUP_ID = "SENTINEL_GROUP";

    private static final String FLOW_SUFFIX = "-flow-rules";
    private static final String DEGRADE_SUFFIX = "-degrade-rules";
    private static final String PARAM_FLOW_SUFFIX = "-param-flow-rules";

    /**
     * 规则内容是标准 JSON，与业务序列化配置无关，因此独立一个 ObjectMapper，
     * 避免被业务上对时间格式、null 处理等定制项影响
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.application.name:unknown}")
    private String appName;

    @Value("${spring.cloud.nacos.config.server-addr:${spring.cloud.nacos.server-addr:}}")
    private String nacosServerAddr;

    @PostConstruct
    public void initRules() {
        if (StringUtils.isEmpty(nacosServerAddr)) {
            log.warn("未配置 Nacos 地址，Sentinel 规则将只保留在内存中，服务重启后失效");
            return;
        }
        // 1.流控规则
        register("FlowRuleManager", appName + FLOW_SUFFIX, FlowRule.class,
                FlowRuleManager::register2Property);
        // 2.熔断降级规则
        register("DegradeRuleManager", appName + DEGRADE_SUFFIX, DegradeRule.class,
                DegradeRuleManager::register2Property);
        // 3.热点参数限流规则
        register("ParamFlowRuleManager", appName + PARAM_FLOW_SUFFIX, ParamFlowRule.class,
                ParamFlowRuleManager::register2Property);
    }

    /**
     * 注册一个 Nacos 数据源并绑定到对应的规则管理器
     *
     * @param managerName 规则管理器名称，仅用于日志
     * @param dataId      Nacos 配置的 dataId
     * @param ruleType    规则类型，用于反序列化
     * @param binder      把数据源绑定到规则管理器的动作
     */
    private <T> void register(String managerName, String dataId, Class<T> ruleType,
                              Consumer<SentinelProperty<List<T>>> binder) {
        try {
            ReadableDataSource<String, List<T>> dataSource =
                    new NacosDataSource<>(nacosServerAddr, GROUP_ID, dataId, jsonListParser(ruleType));
            // NacosDataSource 内部的 property 才是 SentinelProperty，通过 getProperty() 取出来交给规则管理器绑定
            binder.accept(dataSource.getProperty());
            log.info("Sentinel 规则数据源已加载：{} -> {}", dataId, managerName);
        } catch (Exception e) {
            // 数据源加载失败不能阻断启动：没有规则时 Sentinel 表现为"全部放行"，
            // 属于安全降级（业务照常可用），比服务起不来要好
            log.error("Sentinel 规则数据源加载失败，dataId: {}，该类型规则将不生效", dataId, e);
        }
    }

    /**
     * JSON 数组 → 规则列表
     */
    private <T> Converter<String, List<T>> jsonListParser(Class<T> ruleType) {
        JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, ruleType);
        return source -> {
            try {
                return objectMapper.readValue(source, listType);
            } catch (Exception e) {
                // 规则写错时应当立即暴露，否则会静默退化成"无限流"
                throw new IllegalArgumentException("Sentinel 规则解析失败：" + source, e);
            }
        };
    }
}
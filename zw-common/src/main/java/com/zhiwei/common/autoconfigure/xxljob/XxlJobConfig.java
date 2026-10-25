package com.zhiwei.common.autoconfigure.xxljob;

import com.zhiwei.common.utils.StringUtils;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnClass(XxlJobSpringExecutor.class)
@EnableConfigurationProperties(XxlJobProperties.class)
public class XxlJobConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties prop) {
        log.info(">>>>>>>>>>> xxl-job config init.");
        XxlJobSpringExecutor xxlJobSpringExecutor = new XxlJobSpringExecutor();
        XxlJobProperties.Admin admin = prop.getAdmin();
        if (admin != null && StringUtils.isNotEmpty(admin.getAddress())) {
            xxlJobSpringExecutor.setAdminAddresses(admin.getAddress());
        }
        XxlJobProperties.Executor executor = prop.getExecutor();
        if (executor != null) {
            if (executor.getAppName() != null)
                xxlJobSpringExecutor.setAppname(executor.getAppName());
            if (executor.getIp() != null)
                xxlJobSpringExecutor.setIp(executor.getIp());
            // 未配置端口时显式给 0，让 xxl-job 自己挑空闲端口。
            // 原来「不配置就不调 setPort」会落到 XxlJobExecutor 的字段默认值 9999，
            // 于是同一台机器上起第二个带 xxl-job 的服务就 BindException: Address already in use。
            // xxl-job 2.4.2 的 XxlJobExecutor#initEmbedServer 里有
            // `port = port > 0 ? port : NetUtil.findAvailablePort(9999)`，
            // 传 0 会自动从 9999 起找可用端口，并把实际端口注册到 admin，
            // 既不会和业务端口冲突，也不需要给每个服务单独配端口。
            xxlJobSpringExecutor.setPort(executor.getPort() == null ? 0 : executor.getPort());
            if (executor.getLogPath() != null)
                xxlJobSpringExecutor.setLogPath(executor.getLogPath());
            if (executor.getLogRetentionDays() != null)
                xxlJobSpringExecutor.setLogRetentionDays(executor.getLogRetentionDays());
        }
        if (prop.getAccessToken() != null)
            xxlJobSpringExecutor.setAccessToken(prop.getAccessToken());
        log.info(">>>>>>>>>>> xxl-job config end.");
        return xxlJobSpringExecutor;
    }
}

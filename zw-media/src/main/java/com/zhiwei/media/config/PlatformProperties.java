package com.zhiwei.media.config;

import com.zhiwei.media.enums.Platform;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zw.platform")
public class PlatformProperties {
    private Platform file;
    private Platform media;
}

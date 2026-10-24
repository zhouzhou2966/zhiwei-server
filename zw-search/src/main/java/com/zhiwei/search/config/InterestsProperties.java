package com.zhiwei.search.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "zw.interests")
public class InterestsProperties {
    private int topNumber;
}

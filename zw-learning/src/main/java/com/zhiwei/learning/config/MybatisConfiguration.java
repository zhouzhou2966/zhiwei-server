package com.zhiwei.learning.config;


import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.zhiwei.common.autoconfigure.mybatis.MyBatisAutoFillInterceptor;
import com.zhiwei.learning.Utils.TableInfoContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisConfiguration {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页拦截器（必须放在前面）
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor(DbType.MYSQL);
        paginationInnerInterceptor.setMaxLimit(200L);
        interceptor.addInnerInterceptor(paginationInnerInterceptor);
        // 自动填充拦截器（自动注入 creater / updater）
        interceptor.addInnerInterceptor(new MyBatisAutoFillInterceptor());
        // 动态表名拦截器
        DynamicTableNameInnerInterceptor dynamicInterceptor = new DynamicTableNameInnerInterceptor();
        dynamicInterceptor.setTableNameHandler((sql, tableName) -> {
            String dynamicName = TableInfoContext.getInfo();
            if (dynamicName != null && ("points_board".equals(tableName) || "points_record".equals(tableName))) {
                return dynamicName;
            }
            return tableName;
        });
        interceptor.addInnerInterceptor(dynamicInterceptor);
        return interceptor;
    }

}
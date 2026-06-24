package com.eazycount.config.mybatis;

import com.eazycount.entity.DomainFee.PriceMap;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;

@org.springframework.context.annotation.Configuration
public class MyBatisConfig {

    @Bean
    ConfigurationCustomizer myBatisTypeHandlerCustomizer() {
        return (Configuration configuration) -> {
            configuration.getTypeHandlerRegistry().register(PriceMap.class, PriceMapTypeHandler.class);
        };
    }
}

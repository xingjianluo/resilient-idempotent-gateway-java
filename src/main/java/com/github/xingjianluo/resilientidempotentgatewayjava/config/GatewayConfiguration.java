package com.github.xingjianluo.resilientidempotentgatewayjava.config;

import com.github.xingjianluo.resilientidempotentgatewayjava.resilience.ResilienceEngine;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bean 装配：超时执行池与韧性引擎。 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayConfiguration {

    /**
     * 下游调用的超时控制依赖独立线程池（虚拟线程），
     * 与 Tomcat 业务线程隔离，互不影响。
     */
    @Bean(destroyMethod = "close")
    public ExecutorService downstreamTimeoutExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public ResilienceEngine resilienceEngine(ExecutorService downstreamTimeoutExecutor,
                                             GatewayProperties properties) {
        return new ResilienceEngine(downstreamTimeoutExecutor, properties);
    }
}

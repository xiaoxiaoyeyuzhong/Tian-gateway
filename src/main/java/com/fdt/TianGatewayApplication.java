package com.fdt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TianGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TianGatewayApplication.class, args);
    }


        // 这个注解用于创建一个 Spring Bean，即一个路由规则的构建器
        @Bean
        public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
            // 创建路由规则的构建器
            return builder.routes()
                    // 定义路由规则，给该规则起一个名字 "toyupi"
                    .route("toyupi", r -> r.path("/tian")
                            // 将满足 "/tian" 路径的请求转发到 "https://www.code-nav.cn/"
                            .uri("https://www.code-nav.cn/"))
                    // 创建并返回路由规则配置对象
                    .build();
        }
    }



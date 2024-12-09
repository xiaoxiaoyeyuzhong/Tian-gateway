package com.fdt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 1. 用户发送请求到API网关
        // 2. 请求日志
        ServerHttpRequest request = exchange.getRequest();
        log.info("请求唯一标识: request id: {}", request.getId());
        log.info("请求路径: request path: {}", request.getPath());
        log.info("请求方法: request method: {}", request.getMethod());
        log.info("请求参数: request query: {}", request.getQueryParams());
        String sourceAddress = request.getLocalAddress().getHostString();
        log.info("请求来源地址: request source address: {}", sourceAddress);
        log.info("请求来源: request remote address: {}", request.getRemoteAddress());
        // 3. （黑白名单）
        // 拿到响应对象
        ServerHttpResponse response = exchange.getResponse();
        // 判断ip是否在白名单内,如果不在，拒绝访问
        if (!IP_WHITE_LIST.contains(sourceAddress)) {
            // 设置响应状态码为403，拒绝访问
            response.setStatusCode(HttpStatus.FORBIDDEN);
            // 返回处理完成的响应
            return exchange.getResponse().setComplete();
        }
        // 4. 用户鉴权（ak，sk合法性检测）
        // 5. 检测请求的接口是否存在
        // 6. **请求转发，调用接口**
        // 7. 响应日志
        // 8. 调用结果
        //   - 调用成功，接口调用次数+1
        //   - 调用失败，返回规范错误码

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return -1;
    }
}

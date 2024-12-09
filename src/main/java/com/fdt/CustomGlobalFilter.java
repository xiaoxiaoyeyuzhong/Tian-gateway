package com.fdt;

import com.fdt.clientsdk.utils.SignUtils;
import com.fdt.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    @Resource
    RedisUtils redisUtils=  new RedisUtils();
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
            return handleNoAuth(response);
        }
        // 4. 用户鉴权（ak，sk合法性检测）
        HttpHeaders headers = request.getHeaders();
        String accessKey = headers.getFirst("accessKey");
        String nonce = headers.getFirst("nonce");
        String timestamp = headers.getFirst("timestamp");
        String sign = headers.getFirst("sign");
        String body = headers.getFirst("body");
        //校验用户标识 todo 接入后端平台后，应该查询数据库，获取用户标识
        if(!accessKey.equals("fdt")){
            return handleNoAuth(response);
        }
        //随机数过期时间应该在时间戳过期之后，防止同时过期导致漏洞。
        //如果随机数过期删除，时间戳没到过期时间，当这个请求被重放的时候，校验就失效了。
        //校验随机数
        String RedisNonceKey = "com:interface:";
        if(redisUtils.getRedisCache(RedisNonceKey+nonce) != null){
            return handleNoAuth(response);
        }
        //如果随机数未被使用，则存储到redis中
        redisUtils.setRedisCache(RedisNonceKey+nonce,nonce,360, TimeUnit.SECONDS);
        //校验时间戳与当前时间的差距
        if(System.currentTimeMillis()/1000 - Long.parseLong(timestamp) >= 300){
            return handleNoAuth(response);
        }
        //校验签名 todo 接入后端平台后应该查询数据库，获取密钥
        if(!SignUtils.genSign(body, "123456").equals(sign)){
            return handleNoAuth(response);
        }
        // 5. 检测请求的接口是否存在
        // todo 接入后端平台后，应该查询数据库，接口是否存在，请求方法是否匹配，请求参数是否匹配
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

    public Mono<Void> handleNoAuth(ServerHttpResponse response){
        // 设置响应状态码为403，拒绝访问
        response.setStatusCode(HttpStatus.FORBIDDEN);
        // 返回处理完成的响应
        return response.setComplete();
    }
}

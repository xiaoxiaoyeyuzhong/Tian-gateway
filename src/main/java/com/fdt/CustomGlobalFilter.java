package com.fdt;

import com.fdt.clientsdk.utils.SignUtils;
import com.fdt.tianAPICommon.model.entity.InterfaceInfo;
import com.fdt.tianAPICommon.model.entity.User;
import com.fdt.tianAPICommon.service.InnerInterfaceInfoService;
import com.fdt.tianAPICommon.service.InnerUserInterfaceInfoService;
import com.fdt.tianAPICommon.service.InnerUserService;
import com.fdt.utils.RedisUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboReference;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class CustomGlobalFilter implements GlobalFilter, Ordered {

    @DubboReference
    private InnerInterfaceInfoService innerInterfaceInfoService;

    @DubboReference
    private InnerUserService innerUserService;

    @DubboReference
    private InnerUserInterfaceInfoService innerUserInterfaceInfoService;

    private static final List<String> IP_WHITE_LIST = Arrays.asList("127.0.0.1");

    private static final String INTERFACE_HOST = "http://localhost:8123";

    @Resource
    RedisUtils redisUtils = new RedisUtils();
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 1. 用户发送请求到API网关
        // 2. 请求日志
        ServerHttpRequest request = exchange.getRequest();

        String path = INTERFACE_HOST + request.getPath().value();
        String method = request.getMethod().toString();
        log.info("请求唯一标识: request id: {}", request.getId());
        log.info("请求路径: request path: {}", path);
        log.info("请求方法: request method: {}", method);
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
        //校验用户标识
        User invokeUser = null;
        try{
            //调用内部的公共服务，根据accessKey获取用户信息
            invokeUser = innerUserService.getInvokeUser(accessKey);
        } catch (Exception e){
            // 捕获异常，记录日志
            log.error("getInvokeUser error",e);
        }
        if(invokeUser == null){
            // 没有对应accessKey的用户
            return handleNoAuth(response);
        }
        // equals让不为null的字符串调用
//        if(!"fdt".equals(accessKey)){
//            return handleNoAuth(response);
//        }
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
        //校验签名
        // 从获取的用户信息中获取用户的密钥
        String secretKey = invokeUser.getSecretKey();
        // 使用获取的密钥和请求体进行签名
        String serverSign = SignUtils.genSign(body, secretKey);
        if(sign == null || !sign.equals(serverSign)){
            return handleNoAuth(response);
        }
//        if(!SignUtils.genSign(body, "123456").equals(sign)){
//            return handleNoAuth(response);
//        }
        // 5. 检测请求的接口是否存在
        InterfaceInfo interfaceInfo = null;
        try{
            // 尝试根据请求路径和请求方法获取接口信息 todo 数据库添加服务器字段，获取真实的服务器地址
            interfaceInfo = innerInterfaceInfoService.getInterfaceInfo(path, method);
        } catch (Exception e){
            // 捕获异常，记录日志
            log.error("getInterfaceInfo error",e);
        }
        // 检查获取的接口信息是否为空
        if(interfaceInfo == null){
            return handleInvokeError(response);
        }
        // 用户是否还有接口的调用次数

        // 6. **请求转发，调用接口**
//        Mono<Void> filter = chain.filter(exchange);
        return handleResponse(exchange, chain,interfaceInfo.getId(),invokeUser.getId());

//        return filter;
    }

    @Override
    public int getOrder() {
        return -1;
    }

    /**
     * 处理响应
     * @param exchange
     * @param chain
     * @return
     */
    public Mono<Void> handleResponse(ServerWebExchange exchange, GatewayFilterChain chain,long interfaceInfoId,long userId) {
        try {
            // 获取原始的响应对象
            ServerHttpResponse originalResponse = exchange.getResponse();
            // 获取数据缓冲工厂
            DataBufferFactory bufferFactory = originalResponse.bufferFactory();
            // 获取响应的状态码
            HttpStatus statusCode = originalResponse.getStatusCode();

            // 判断状态码是否为200 OK(按道理来说,现在没有调用,是拿不到响应码的,对这个保持怀疑 沉思.jpg)
            if(statusCode == HttpStatus.OK) {
                // 创建一个装饰后的响应对象(开始穿装备，增强能力)
                ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {

                    // 重写writeWith方法，用于处理响应体的数据
                    // 这段方法就是只要当我们的模拟接口调用完成之后,等它返回结果，
                    // 就会调用writeWith方法,我们就能根据响应结果做一些自己的处理
                    @Override
                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                        log.info("body instanceof Flux: {}", (body instanceof Flux));
                        // 判断响应体是否是Flux类型
                        if (body instanceof Flux) {
                            Flux<? extends DataBuffer> fluxBody = Flux.from(body);
                            // 返回一个处理后的响应体
                            // (这里就理解为它在拼接字符串,它把缓冲区的数据取出来，一点一点拼接好)
                            return super.writeWith(fluxBody.map(dataBuffer -> {
                                // 调用成功，接口调用次数+1
                                try{
                                    innerUserInterfaceInfoService.invokeCount(interfaceInfoId,userId);
                                }catch (Exception e){
                                    log.error("invokeCount error",e);
                                }
                                // 读取响应体的内容并转换为字节数组
                                byte[] content = new byte[dataBuffer.readableByteCount()];
                                dataBuffer.read(content);
                                DataBufferUtils.release(dataBuffer);//释放掉内存
                                // 构建日志
                                StringBuilder sb2 = new StringBuilder(200);
                                //sb2.append("<--- {} {} \n");
                                List<Object> rspArgs = new ArrayList<>();
                                rspArgs.add(originalResponse.getStatusCode());
                                //rspArgs.add(requestUrl);
                                String data = new String(content, StandardCharsets.UTF_8);//data
                                sb2.append(data);
                                //log.info(sb2.toString(), rspArgs.toArray());//log.info("<-- {} {}\n", originalResponse.getStatusCode(), data);
                                // 打印日志
                                log.info("响应结果："+ data);
                                // 将处理后的内容重新包装成DataBuffer并返回
                                return bufferFactory.wrap(content);
                            }));
                        } else {
                            // 调用失败，返回规范错误码
                            log.error("<--- {} 响应code异常", getStatusCode());
                        }
                        return super.writeWith(body);
                    }
                };
                // 对于200 OK的请求,将装饰后的响应对象传递给下一个过滤器链,并继续处理(设置repsonse对象为装饰过的)
                return chain.filter(exchange.mutate().response(decoratedResponse).build());
            }
            // 对于非200 OK的请求，直接返回，进行降级处理
            return chain.filter(exchange);
        }catch (Exception e){
            // 处理异常情况，记录错误日志
            log.error("网关处理响应异常" + e);
            return chain.filter(exchange);
        }
    }


    // 拒绝访问，设置状态码方法
    public Mono<Void> handleNoAuth(ServerHttpResponse response){
        // 设置响应状态码为403，拒绝访问
        response.setStatusCode(HttpStatus.FORBIDDEN);
        // 返回处理完成的响应
        return response.setComplete();
    }

    // 调用错误，设置状态码方法
    public Mono<Void> handleInvokeError(ServerHttpResponse response){
        // 设置响应状态码为500，调用失败
        response.setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        // 返回处理完成的响应
        return response.setComplete();
    }

}

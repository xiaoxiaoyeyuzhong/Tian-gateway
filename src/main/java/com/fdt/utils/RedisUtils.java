package com.fdt.utils;

import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.TimeUnit;

@Log4j2
@Component
public class RedisUtils {

    //读写redis需要的变量
    @Resource
    private RedisTemplate<String, Object> redisTemplate;


    private ValueOperations<String, Object> valueOperations;

    public RedisUtils() {
    }


    /**
     * 数据缓存，将键值对存入Redis中
     *
     * @param key   键
     * @param value 值
     * @param time  过期时间
     * @param unit  时间单位
     */
    public void setRedisCache(String key, Object value, long time, TimeUnit unit) {
        if (valueOperations == null) {
            valueOperations = redisTemplate.opsForValue();
        }
        try {
            valueOperations.set(key, value, time, unit);
        } catch (Exception e) {
            log.error("redis set key error" + e);
        }
    }

    /**
     * 获取缓存数据
     * @param key 键
     * @return value 值
     */
    public Object getRedisCache(String key) {
        if (valueOperations == null) {
            valueOperations = redisTemplate.opsForValue();
        }
        try {
            return valueOperations.get(key);
        } catch (Exception e) {
            log.error("redis get key error" + e);
        }
        return null;
    }
}

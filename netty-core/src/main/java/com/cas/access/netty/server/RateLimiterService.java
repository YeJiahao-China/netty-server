//package com.cas.access.netty.server;
//
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.data.redis.connection.ReturnType;
//import org.springframework.data.redis.core.RedisCallback;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Component;
//
//@Component
//public class RateLimiterService {
//
//    @Autowired
//    private StringRedisTemplate redisTemplate;
//
//    /**
//     * 尝试获取许可：设备是否可以在当前时间上报数据
//     * @param mn 设备编码
//     * @return true 表示允许，false 表示被限流
//     */
//    public boolean tryAcquire(String mn) {
//        String key = "rate_limit:device:" + mn;
//        long now = System.currentTimeMillis();
//        long windowMs = 60_000;  // 60秒
//        long maxCount = 2;       // 最多2次
//
//        // 加载 Lua 脚本（建议从文件加载）
//        String script = "...上面的Lua脚本...";
//
//        Long result = (Long) redisTemplate.execute(
//                (RedisCallback<Long>) connection -> {
//                    return (Long) connection.eval(
//                            script.getBytes(),
//                            ReturnType.INTEGER,
//                            1,
//                            key.getBytes(),
//                            String.valueOf(now).getBytes(),
//                            String.valueOf(windowMs).getBytes(),
//                            String.valueOf(maxCount).getBytes()
//                    );
//                }
//        );
//
//        return result != null && result == 1;
//    }
//}

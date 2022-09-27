package com.newland.redislock.service.impl;

import com.newland.redislock.service.IPaymentService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class PaymentServiceImpl implements IPaymentService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Override
    public String payment(String account, int money, String orderId) {
        //redis 核心原理还是setnx orderId TokenLock 命令方式
        boolean lock = redisTemplate.opsForValue().setIfAbsent(orderId, "lock");
        if (!lock) {
            return "锁具被上锁";
        }
        try {
            int balance = Integer.valueOf(redisTemplate.opsForValue().get("balance"));
            if (balance - money > 0) {
                redisTemplate.opsForValue().set("balance", String.valueOf(balance - money));
                return "扣减成功";
            } else {
                return "扣减失败，金额不足";
            }
        } finally {
            redisTemplate.delete(orderId);
        }
    }

    /**
     * 针对payment的优化，防止订单锁执行成功后，redis 宕机或者代码异常导致订单锁未被成功释放，设置过期时间来解决
     */
    public String payment2(String account, int money, String orderId) {
        //redis 核心原理还是setnx orderId TokenLock 命令方式
        boolean lock = redisTemplate.opsForValue().setIfAbsent(orderId, "lock", 30, TimeUnit.SECONDS);
        if (!lock) {
            return "锁具被上锁";
        }
        try {
            int balance = Integer.valueOf(redisTemplate.opsForValue().get("balance"));
            if (balance - money > 0) {
                redisTemplate.opsForValue().set("balance", String.valueOf(balance - money));
                return "扣减成功";
            } else {
                return "扣减失败，金额不足";
            }
        } finally {
            redisTemplate.delete(orderId);
        }
    }

    /**
     * 虽然payment2解决了redis宕机或者异常导致锁不能释放的问题，但还有其它问题，
     */
    public String payment3(String account, int money, String orderId) {
        String uuid = UUID.randomUUID().toString();
        boolean lock = redisTemplate.opsForValue().setIfAbsent(orderId, uuid, 30, TimeUnit.SECONDS);
        if (!lock) {
            return "锁具被上锁";
        }
        try {
            int balance = Integer.valueOf(redisTemplate.opsForValue().get("balance"));
            if (balance - money > 0) {
                redisTemplate.opsForValue().set("balance", String.valueOf(balance - money));
                return "扣减成功";
            } else {
                return "扣减失败，金额不足";
            }
        } finally {
            if (uuid.equals(redisTemplate.opsForValue().get(orderId))) {
                redisTemplate.delete(orderId);
            }
        }
    }

    public String payment4(String account, int money, String orderId) {
        RLock lock = redissonClient.getLock(orderId);
        try {
            lock.lock();
            int balance = Integer.valueOf(redisTemplate.opsForValue().get("balance"));
            if (balance - money > 0) {
                redisTemplate.opsForValue().set("balance", String.valueOf(balance - money));
                return "扣减成功";
            } else {
                return "扣减失败，金额不足";
            }
        } finally {
            lock.unlock();
        }
    }
}

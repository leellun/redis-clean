package com.newland.redis.cache.service.impl;

import com.alibaba.fastjson.JSON;
import com.newland.redis.cache.common.RedisKeyPrefixconst;
import com.newland.redis.cache.common.RedisUtil;
import com.newland.redis.cache.entity.Product;
import com.newland.redis.cache.mapper.ProductMapper;
import com.newland.redis.cache.service.ProductService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.Redisson;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.newland.redis.cache.common.RedisKeyPrefixconst.PRODUCT_CACHE_BLOOM_FILTER;

/**
 * 高并发之redisson分布式锁的方案实现
 *
 * @author leellun
 * @since 2022-09-25
 */
@Service
public class ProductServiceImpl extends ServiceImpl<ProductMapper, Product> implements ProductService {
    @Autowired
    private RedisUtil redisutil;
    @Autowired
    private RedissonClient redissonClient;
    public static final Long PRODUCT_CACHE_TIMEOUT = 60 * 60 * 24L;
    public static final String EMPTY_CACHE = "{}";
    public static final String LOCK_PRODUCT_HOT_CACHE_CLEAR_PREFIX = "lock:product:hot_cache_create:";
    public static final String LOCK_PRODUCT_UPDATE_PREFIX = "lock:product:update:";
    /**
     * JVM进程级别缓存
     */
    private Map<String, Product> cacheMap = new ConcurrentHashMap<>();

    @Transactional
    @Override
    public Product create(Product product) {
        baseMapper.insert(product);
        redisutil.set(RedisKeyPrefixconst.PRODUCT_CACHE + product.getId(), JSON.toJSONString(product), getProductCacheTimeout());
        return product;
    }

    @Override
    public Product update(Product product) {
        RLock productUpdateLock = redissonClient.getReadWriteLock(LOCK_PRODUCT_UPDATE_PREFIX + product.getId()).writeLock();
        productUpdateLock.lock();
        try {
            baseMapper.updateById(product);
            redisutil.set(RedisKeyPrefixconst.PRODUCT_CACHE + product.getId(), JSON.toJSONString(product), getProductCacheTimeout());
        } finally {
            productUpdateLock.unlock();
        }
        return product;
    }

    /**
     * double checked locking
     *
     * @param productId
     * @return
     */
    @Override
    public Product getProduct(Long productId) {
        String productCacheKey = RedisKeyPrefixconst.PRODUCT_CACHE + productId;
        Product product = getProductFromCache(productCacheKey);
        if (product != null) {
            if (product.getId() == null) return null;
            return product;
        }
        RLock hotCacheCreateLock = redissonClient.getLock(LOCK_PRODUCT_HOT_CACHE_CLEAR_PREFIX + productId);
        hotCacheCreateLock.lock();
        //如果采用trylock，当数据库查找存在偶尔延迟操作可以提高效率；弊端：存在同一时刻查询mysql量陡增的风险
//        hotCacheCreateLock.tryLock(3, TimeUnit.MINUTES);
        try {
            product = getProductFromCache(productCacheKey);
            if (product != null) {
                if (product.getId() == null) return null;
                return product;
            }
            RReadWriteLock productUpdateLock = redissonClient.getReadWriteLock(LOCK_PRODUCT_UPDATE_PREFIX + product.getId());
            RLock rLock = productUpdateLock.readLock();
            try {
                /**
                 * 数据库数据查询
                 */
                product = baseMapper.selectById(productId);
                if (product != null) {
                    redisutil.set(productCacheKey, JSON.toJSONString(product), getProductCacheTimeout());
                    cacheMap.put(productCacheKey, product);
                } else {
                    RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(PRODUCT_CACHE_BLOOM_FILTER + productId);
                    bloomFilter.tryInit(productId, 0.03);
                    bloomFilter.add(productId);
                    bloomFilter.contains(productId);
                    /**
                     * 防止内存穿透 带来的性能消耗问题
                     */
                    redisutil.set(productCacheKey, EMPTY_CACHE, getEmptyCacheTimeout());
                    cacheMap.put(productCacheKey, new Product());
                }
            } finally {
                cacheMap.remove(productCacheKey);
                rLock.unlock();
            }
        } finally {
            hotCacheCreateLock.unlock();
        }


        return product;
    }

    /**
     * 高并发之redisson分布式锁的方案实现
     * @param productId
     * @return
     */
    public Product getProduct2(Long productId) {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(PRODUCT_CACHE_BLOOM_FILTER);
        bloomFilter.tryInit(100000000L, 0.03);
        String productCacheKey = RedisKeyPrefixconst.PRODUCT_CACHE + productId;
        Product product = getProductFromCache(productCacheKey);
        if (product != null || bloomFilter.contains(productId)) {
            return product;
        }
        RLock hotCacheCreateLock = redissonClient.getLock(LOCK_PRODUCT_HOT_CACHE_CLEAR_PREFIX + productId);
        hotCacheCreateLock.lock();
        //如果采用trylock，当数据库查找存在偶尔延迟操作可以提高效率；弊端：存在同一时刻查询mysql量陡增的风险
//        hotCacheCreateLock.tryLock(3, TimeUnit.MINUTES);
        try {
            product = getProductFromCache(productCacheKey);
            if (product != null || bloomFilter.contains(productId)) {
                return product;
            }
            RReadWriteLock productUpdateLock = redissonClient.getReadWriteLock(LOCK_PRODUCT_UPDATE_PREFIX + product.getId());
            RLock rLock = productUpdateLock.readLock();
            try {
                /**
                 * 数据库数据查询
                 */
                product = baseMapper.selectById(productId);
                if (product != null) {
                    redisutil.set(productCacheKey, JSON.toJSONString(product), getProductCacheTimeout());
                    cacheMap.put(productCacheKey, product);
                } else {
                    bloomFilter.add(productId);
                }
            } finally {
                cacheMap.remove(productCacheKey);
                rLock.unlock();
            }
        } finally {
            hotCacheCreateLock.unlock();
        }


        return product;
    }

    private Product getProductFromCache(String productCacheKey) {
        /**
         * JVM进程级别缓存
         */
        Product product = cacheMap.get(productCacheKey);
        if (product != null) {
            return product;
        }
        /**
         * redis 内存数据库级别缓存
         */
        String productStr = (String) redisutil.get(productCacheKey);
        if (!StringUtils.isEmpty(productStr)) {
            if (EMPTY_CACHE.equals(productStr)) {
                return new Product();
            }
            product = JSON.parseObject(productStr, Product.class);
        }
        return product;
    }

    private Long getProductCacheTimeout() {
        return PRODUCT_CACHE_TIMEOUT + new Random().nextInt(30) * 60;
    }

    private Long getEmptyCacheTimeout() {
        return 60L + new Random().nextInt(30);
    }
}

package com.newland.redis.cache.service;

import com.newland.redis.cache.entity.Product;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author leellun
 * @since 2022-09-25
 */
public interface ProductService extends IService<Product> {

    Product create(Product product);

    Product update(Product product);

    Product getProduct(Long projectId);
}

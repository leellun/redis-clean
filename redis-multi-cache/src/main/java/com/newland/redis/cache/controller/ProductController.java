package com.newland.redis.cache.controller;


import com.newland.redis.cache.entity.Product;
import com.newland.redis.cache.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author leellun
 * @since 2022-09-25
 */
@RestController
@RequestMapping("/api/product")
public class ProductController {
    @Autowired
    private ProductService productService;

    @RequestMapping(value = "/create", method = RequestMethod.POST)
    public Product create(@RequestBody Product product) {
        return productService.create(product);
    }

    @RequestMapping(value = "/update", method = RequestMethod.PUT)
    public Product update(@RequestBody Product product) {
        return productService.update(product);
    }

    @RequestMapping(value = "/get/{projectId}")
    public Product getProduct(@PathVariable Long projectId) {
        return productService.getProduct(projectId);
    }
}


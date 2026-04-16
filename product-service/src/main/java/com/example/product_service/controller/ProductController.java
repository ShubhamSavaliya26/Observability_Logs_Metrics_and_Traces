
        package com.example.product_service.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.product_service.model.Product;
import com.example.product_service.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);
    private final ProductRepository repo;

    @GetMapping
    public List<Product> all() {
        log.info("Request received to retrieve all products");
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public Product one(@PathVariable Long id) {

        log.info("Received request to fetch product with id: {}", id);

        return repo.findById(id)
                .map(product -> {
                    log.info("Product found with id: {}, name: {}, price: {}",
                            product.getId(), product.getName(), product.getPrice());
                    return product;
                })

                .orElseGet(() ->
                {
                    log.warn("Product not found for id: {}", id);
                    log.error("Failed to retrieve product because no product exists for id: {}", id);
                    throw new RuntimeException("Product not found for id " + id);
                });
    }

    @PostMapping
    public Product create(@RequestBody Product p) {

        log.info("Received request to create product with name: {}", p.getName());

        Product saved = repo.save(p);

        log.info("Product created successfully with id: {}", saved.getId());

        return saved;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {

        log.info("Received request to delete product with id: {}", id);

        repo.deleteById(id);

        log.info("Product deleted successfully with id: {}", id);
    }
}
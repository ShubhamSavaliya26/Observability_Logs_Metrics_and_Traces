package com.example.order_service.controller;

import com.example.order_service.dto.Product;
import com.example.order_service.model.Orders;
import com.example.order_service.repository.OrderRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository repo;
    private final RestTemplate restTemplate;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Counter ordersCreatedCounter;

    @Value("${product.service.url}")
    private String productUrl;

    public OrderController(
            OrderRepository repo,
            RestTemplate restTemplate,
            MeterRegistry meterRegistry,
            Tracer tracer,
            Propagator propagator
    ) {
        this.repo = repo;
        this.restTemplate = restTemplate;
        this.tracer = tracer;
        this.propagator = propagator;
        this.ordersCreatedCounter = Counter.builder("orders_created_total")
                .description("Total number of orders created")
                .register(meterRegistry);
    }

    @GetMapping
    public List<Orders> all() {
        log.info("Request received to retrieve all orders");
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public Orders one(@PathVariable Long id) {
        log.info("Request received to fetch order with id: {}", id);

        return repo.findById(id)
                .orElseThrow(() -> {
                    log.warn("Order not found for id: {}", id);
                    log.error("Failed to retrieve order because no order exists for id: {}", id);
                    return new RuntimeException("Order not found for id " + id);
                });
    }

    @PostMapping
    public Orders create(@RequestBody Orders order) {

        log.info("Received request to create order for productId: {}, quantity: {}",
                order.getProductId(), order.getQuantity());

        String correlationId = MDC.get("correlationId");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
            MDC.put("correlationId", correlationId);
        }


        log.info("Calling product-service at {} to retrieve product with id: {}",
                productUrl, order.getProductId());

        Product product;
        Span customSpan = tracer.nextSpan().name("call-product-service");

        try (Tracer.SpanInScope ws = tracer.withSpan(customSpan.start())) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Correlation-Id", correlationId);
            propagator.inject(customSpan.context(), headers, HttpHeaders::set);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<Product> response = restTemplate.exchange(
                    productUrl + "/" + order.getProductId(),
                    HttpMethod.GET,
                    entity,
                    Product.class
            );

            product = response.getBody();

            log.info("Product-service call completed for productId: {}", order.getProductId());

        } catch (Exception ex) {
            log.warn("Product unavailable or could not be retrieved for productId: {}",
                    order.getProductId());
            log.error("Order creation failed while calling product-service for productId: {}. Reason: {}",
                    order.getProductId(), ex.getMessage());
            throw new RuntimeException("Product unavailable", ex);
        } finally {
            customSpan.end();
        }

        if (product == null || product.getQuantity() < order.getQuantity()) {
            log.warn("Product unavailable or insufficient quantity for productId: {}",
                    order.getProductId());
            log.error("Order creation failed because productId {} is unavailable or does not have enough quantity",
                    order.getProductId());
            throw new RuntimeException("Product unavailable");
        }

        order.setTotalPrice(product.getPrice() * order.getQuantity());
        order.setStatus("CREATED");

        Orders saved = repo.save(order);

        ordersCreatedCounter.increment();

        log.info("Order created successfully with id: {}", saved.getId());

        return saved;
    }
}

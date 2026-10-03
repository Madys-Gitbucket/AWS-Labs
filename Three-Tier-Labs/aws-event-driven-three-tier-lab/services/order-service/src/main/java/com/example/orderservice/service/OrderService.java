package com.example.orderservice.service;

import com.example.orderservice.model.Order;
import com.example.orderservice.repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequest;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

import java.util.Map;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${eventbridge.bus-name:default}")
    private String eventBusName;

    @Value("${aws.region:eu-west-2}")
    private String awsRegion;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public Order createOrder(Order order) {
        order.setStatus("CREATED");
        Order saved = repository.save(order);
        publishOrderCreated(saved);
        return saved;
    }

    private void publishOrderCreated(Order order) {
        try {
            String detail = objectMapper.writeValueAsString(Map.of(
                "orderId",    order.getId(),
                "customerId", order.getCustomerId(),
                "productId",  order.getProductId(),
                "quantity",   order.getQuantity(),
                "status",     order.getStatus()
            ));

            PutEventsRequestEntry entry = PutEventsRequestEntry.builder()
                .eventBusName(eventBusName)
                .source("com.example.order-service")
                .detailType("OrderCreated")
                .detail(detail)
                .build();

            try (EventBridgeClient client = EventBridgeClient.builder()
                    .region(Region.of(awsRegion))
                    .build()) {
                var response = client.putEvents(
                    PutEventsRequest.builder().entries(entry).build());
                if (response.failedEntryCount() > 0) {
                    log.error("Failed to publish event for orderId={}", order.getId());
                } else {
                    log.info("Published OrderCreated for orderId={}", order.getId());
                }
            }
        } catch (Exception e) {
            // Log but do not fail the order creation — event is best-effort in this lab
            log.error("Event publish failed for orderId={}: {}", order.getId(), e.getMessage());
        }
    }
}

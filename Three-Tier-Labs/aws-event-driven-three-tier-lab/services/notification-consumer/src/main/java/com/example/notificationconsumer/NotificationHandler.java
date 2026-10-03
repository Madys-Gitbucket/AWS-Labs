package com.example.notificationconsumer;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lambda function triggered by SQS messages from the order-events bus.
 *
 * Each SQS record body is an EventBridge event JSON. The handler extracts
 * the order details from the "detail" field and logs a notification.
 *
 * In a real system this would: send an email via SES, push to SNS, write
 * to a notification table, or call a third-party service.
 *
 * Retry behaviour: if handleRequest throws, SQS does NOT delete the message.
 * After maxReceiveCount failures, the message moves to the DLQ.
 */
public class NotificationHandler implements RequestHandler<SQSEvent, Void> {

    private static final Logger log = LoggerFactory.getLogger(NotificationHandler.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        for (SQSEvent.SQSMessage record : event.getRecords()) {
            try {
                processRecord(record.getBody());
            } catch (Exception e) {
                log.error("Failed processing messageId={}: {}",
                    record.getMessageId(), e.getMessage(), e);
                // Re-throw to prevent SQS from deleting this message.
                // SQS will retry; after maxReceiveCount the message goes to DLQ.
                throw new RuntimeException("Processing failed for " + record.getMessageId(), e);
            }
        }
        return null;
    }

    private void processRecord(String body) throws Exception {
        JsonNode root = mapper.readTree(body);

        // EventBridge wraps the payload — the application data is in "detail"
        JsonNode detail = root.path("detail");
        if (detail.isMissingNode()) {
            // Direct SQS test message (not from EventBridge)
            detail = root;
        }

        String orderId    = detail.path("orderId").asText("unknown");
        String customerId = detail.path("customerId").asText("unknown");
        long   productId  = detail.path("productId").asLong(0);
        int    quantity   = detail.path("quantity").asInt(0);

        // TODO (production): send email via SES, push push notification, etc.
        log.info("ORDER NOTIFICATION — orderId={} customerId={} productId={} quantity={}",
            orderId, customerId, productId, quantity);
    }
}

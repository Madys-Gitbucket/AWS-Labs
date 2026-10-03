# Event-Driven Three-Tier Lab

Extension of Labs 1–3. The three-tier architecture gains an **async event-driven layer**: when an order is created, the order-service publishes an `OrderCreated` event to EventBridge, which routes it through SQS to a Lambda notification consumer.

Full setup guide: [docs/setup-guide.md](docs/setup-guide.md)

---

## What changes from Lab 3

```
Lab 3:  POST /orders → ECS order-service → RDS (synchronous only)

Lab 4:  POST /orders → ECS order-service → RDS
                              │
                              └─ PutEvents
                                    │
                                    ▼
                              EventBridge bus
                                    │  rule: source=order-service, type=OrderCreated
                                    ▼
                              SQS queue (with DLQ)
                                    │  event source mapping
                                    ▼
                              Lambda notification-consumer
                              (logs notification; production: sends email/SMS)
```

---

## New services

| Service | Type | Role |
|---------|------|------|
| `order-service` | Spring Boot on ECS Fargate | Replaces `product-service`; persists orders to RDS and publishes events |
| `notification-consumer` | Java Lambda | Receives OrderCreated events from SQS, logs notification |

---

## New AWS resources

| Resource | Detail |
|----------|--------|
| EventBridge custom bus | `lab-order-events` — isolates app events from AWS service events |
| EventBridge rule | Filters `source=com.example.order-service`, `detail-type=OrderCreated` |
| SQS queue | `lab-order-queue` — buffers events, provides retry semantics |
| SQS DLQ | `lab-order-dlq` — captures messages after 3 failed Lambda attempts |
| Lambda | `lab-notification-consumer` — Java 21, SQS event source mapping |
| IAM roles | ECS task role (EventBridge PutEvents), Lambda role (SQS read/delete) |

---

## Lab Series

| Lab | Repo | Focus |
|-----|------|-------|
| Lab 1 | aws-three-tier-web-app-lab | VPC + EC2 + RDS |
| Lab 2 | aws-ecs-three-tier-lab | ECS Fargate |
| Lab 3 | aws-ecs-cicd-three-tier-lab | CI/CD |
| **Lab 4 (this)** | aws-event-driven-three-tier-lab | EventBridge + SQS + Lambda |

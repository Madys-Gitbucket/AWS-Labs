# Setup Guide — Event-Driven Three-Tier Lab

This lab extends Labs 1–3. The VPC, RDS, ALB, and ECS infrastructure remain. This guide adds:
- An EventBridge custom bus and rule
- An SQS queue and dead letter queue
- A Lambda notification consumer
- Updated IAM permissions for the ECS task to publish events

---

## Part A — Build the Lambda JAR first

The Lambda JAR must exist before you create the Lambda function.

```bash
cd services/notification-consumer
mvn package
ls target/notification-consumer.jar
cd ../..
```

---

## Part B — SQS Queues

### Step B.1 — Create the Dead Letter Queue

**Console:**
1. **SQS** → **Create queue**.
2. Type: **Standard**.
3. Name: `lab-order-dlq`
4. Message retention: **14 days** (1209600 seconds).
5. All other settings: default.
6. **Create queue**.
7. Copy the **ARN** from the queue detail page — you need it for Step B.2.

**CLI:**
```bash
DLQ_URL=$(aws sqs create-queue \
  --queue-name lab-order-dlq \
  --attributes MessageRetentionPeriod=1209600 \
  --query 'QueueUrl' --output text --region eu-west-2)

DLQ_ARN=$(aws sqs get-queue-attributes \
  --queue-url $DLQ_URL \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text --region eu-west-2)
echo "DLQ ARN: $DLQ_ARN"
```

### Step B.2 — Create the main order queue

**Console:**
1. **SQS** → **Create queue** → Standard.
2. Name: `lab-order-queue`
3. Visibility timeout: **180 seconds** (must be ≥ Lambda timeout × 6).
4. Message retention: **1 day** (86400 seconds).
5. Scroll to **Dead-letter queue** → **Enabled** → ARN: paste the `lab-order-dlq` ARN → Maximum receives: **3**.
6. **Create queue**.
7. Copy this queue's **ARN** — needed for the EventBridge target and Lambda source mapping.

**CLI:**
```bash
QUEUE_URL=$(aws sqs create-queue \
  --queue-name lab-order-queue \
  --attributes "{
    \"VisibilityTimeout\":\"180\",
    \"MessageRetentionPeriod\":\"86400\",
    \"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
  }" \
  --query 'QueueUrl' --output text --region eu-west-2)

QUEUE_ARN=$(aws sqs get-queue-attributes \
  --queue-url $QUEUE_URL \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' --output text --region eu-west-2)
echo "Queue ARN: $QUEUE_ARN"
```

---

## Part C — EventBridge

### Step C.1 — Create the custom event bus

**Console:**
1. **EventBridge** → **Event buses** → **Create event bus**.
2. Name: `lab-order-events`
3. **Create**.

**CLI:**
```bash
aws events create-event-bus \
  --name lab-order-events \
  --region eu-west-2
```

### Step C.2 — Create the routing rule

**Console:**
1. **EventBridge** → **Rules** → **Create rule**.
2. Name: `lab-order-created-rule`
3. Event bus: **lab-order-events** (not the default bus).
4. Rule type: **Rule with an event pattern**.
5. Event pattern — switch to **Custom pattern (JSON editor)** and paste:
   ```json
   {
     "source": ["com.example.order-service"],
     "detail-type": ["OrderCreated"]
   }
   ```
6. **Next** → Target: **SQS queue** → Queue: `lab-order-queue`.
7. **Next** → **Next** → **Create rule**.

**CLI:**
```bash
aws events put-rule \
  --name lab-order-created-rule \
  --event-bus-name lab-order-events \
  --event-pattern '{"source":["com.example.order-service"],"detail-type":["OrderCreated"]}' \
  --state ENABLED \
  --region eu-west-2

aws events put-targets \
  --rule lab-order-created-rule \
  --event-bus-name lab-order-events \
  --targets "Id=order-queue,Arn=${QUEUE_ARN}" \
  --region eu-west-2
```

### Step C.3 — Allow EventBridge to send to SQS

The SQS queue needs a resource policy permitting EventBridge to deliver messages.

**Console:**
1. **SQS** → `lab-order-queue` → **Access policy** tab → **Edit**.
2. Add this statement to the existing policy (inside the `Statement` array), replacing `<ACCOUNT_ID>` and `<RULE_ARN>`:
   ```json
   {
     "Sid": "AllowEventBridge",
     "Effect": "Allow",
     "Principal": {"Service": "events.amazonaws.com"},
     "Action": "sqs:SendMessage",
     "Resource": "<QUEUE_ARN>",
     "Condition": {
       "ArnEquals": {"aws:SourceArn": "<RULE_ARN>"}
     }
   }
   ```
3. **Save**.

**CLI:**
```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
RULE_ARN="arn:aws:events:eu-west-2:${ACCOUNT_ID}:rule/lab-order-events/lab-order-created-rule"

aws sqs set-queue-attributes \
  --queue-url $QUEUE_URL \
  --attributes "{\"Policy\":\"{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[{\\\"Sid\\\":\\\"AllowEventBridge\\\",\\\"Effect\\\":\\\"Allow\\\",\\\"Principal\\\":{\\\"Service\\\":\\\"events.amazonaws.com\\\"},\\\"Action\\\":\\\"sqs:SendMessage\\\",\\\"Resource\\\":\\\"${QUEUE_ARN}\\\",\\\"Condition\\\":{\\\"ArnEquals\\\":{\\\"aws:SourceArn\\\":\\\"${RULE_ARN}\\\"}}}]}\"}" \
  --region eu-west-2
```

---

## Part D — Lambda Function

### Step D.1 — Create Lambda execution role

**Console:**
1. **IAM** → **Roles** → **Create role** → **AWS service** → **Lambda** → **Next**.
2. Attach managed policy: `AWSLambdaBasicExecutionRole`.
3. Role name: `lab-lambda-notification-role` → **Create role**.
4. Open the role → **Add permissions** → **Create inline policy** → JSON:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Sid": "SQSConsume",
       "Effect": "Allow",
       "Action": ["sqs:ReceiveMessage","sqs:DeleteMessage","sqs:GetQueueAttributes"],
       "Resource": "<QUEUE_ARN>"
     }]
   }
   ```
5. Policy name: `SQSConsume` → **Create policy**.

**CLI:**
```bash
aws iam create-role \
  --role-name lab-lambda-notification-role \
  --assume-role-policy-document '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"lambda.amazonaws.com"},"Action":"sts:AssumeRole"}]}'

aws iam attach-role-policy \
  --role-name lab-lambda-notification-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

aws iam put-role-policy \
  --role-name lab-lambda-notification-role \
  --policy-name SQSConsume \
  --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":[\"sqs:ReceiveMessage\",\"sqs:DeleteMessage\",\"sqs:GetQueueAttributes\"],\"Resource\":\"${QUEUE_ARN}\"}]}"

LAMBDA_ROLE_ARN=$(aws iam get-role \
  --role-name lab-lambda-notification-role \
  --query 'Role.Arn' --output text)
```

### Step D.2 — Create CloudWatch log group

**Console:** **CloudWatch** → **Log groups** → **Create log group** → `/aws/lambda/lab-notification-consumer` → Retention: **7 days** → **Create**.

```bash
aws logs create-log-group \
  --log-group-name /aws/lambda/lab-notification-consumer --region eu-west-2
aws logs put-retention-policy \
  --log-group-name /aws/lambda/lab-notification-consumer \
  --retention-in-days 7 --region eu-west-2
```

### Step D.3 — Create the Lambda function

**Console:**
1. **Lambda** → **Create function** → **Author from scratch**.
2. Function name: `lab-notification-consumer`
3. Runtime: **Java 21**
4. Architecture: **x86_64**
5. Permissions: **Use an existing role** → `lab-lambda-notification-role`
6. **Create function**.
7. In the function configuration → **Code** tab → **Upload from** → **.zip or .jar file** → upload `services/notification-consumer/target/notification-consumer.jar`.
8. **Runtime settings** → **Edit** → Handler: `com.example.notificationconsumer.NotificationHandler::handleRequest` → **Save**.
9. **Configuration** tab → **General configuration** → **Edit** → Memory: **512 MB**, Timeout: **30 seconds** → **Save**.

**CLI:**
```bash
aws lambda create-function \
  --function-name lab-notification-consumer \
  --runtime java21 \
  --handler com.example.notificationconsumer.NotificationHandler::handleRequest \
  --role $LAMBDA_ROLE_ARN \
  --zip-file fileb://services/notification-consumer/target/notification-consumer.jar \
  --memory-size 512 \
  --timeout 30 \
  --region eu-west-2

aws lambda wait function-active \
  --function-name lab-notification-consumer --region eu-west-2
```

### Step D.4 — Add SQS as a trigger (event source mapping)

**Console:**
1. On the Lambda function page → **Configuration** → **Triggers** → **Add trigger**.
2. Source: **SQS**.
3. SQS queue: `lab-order-queue`.
4. Batch size: **10**.
5. Batch window: **5 seconds**.
6. Activate trigger: **Enabled**.
7. **Add**.

**CLI:**
```bash
aws lambda create-event-source-mapping \
  --function-name lab-notification-consumer \
  --event-source-arn $QUEUE_ARN \
  --batch-size 10 \
  --maximum-batching-window-in-seconds 5 \
  --bisect-on-function-error \
  --region eu-west-2
```

---

## Part E — Update ECS Task Role (PutEvents permission)

The order-service ECS task needs permission to call `events:PutEvents` on the custom bus.

**Console:**
1. **IAM** → **Roles** → `lab-ecs-task-role` → **Add permissions** → **Create inline policy**.
2. JSON:
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [{
       "Sid": "EventBridgePutEvents",
       "Effect": "Allow",
       "Action": "events:PutEvents",
       "Resource": "arn:aws:events:eu-west-2:<ACCOUNT_ID>:event-bus/lab-order-events"
     }]
   }
   ```
3. Policy name: `EventBridgePutEvents` → **Create policy**.

**CLI:**
```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws iam put-role-policy \
  --role-name lab-ecs-task-role \
  --policy-name EventBridgePutEvents \
  --policy-document "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"EventBridgePutEvents\",\"Effect\":\"Allow\",\"Action\":\"events:PutEvents\",\"Resource\":\"arn:aws:events:eu-west-2:${ACCOUNT_ID}:event-bus/lab-order-events\"}]}"
```

---

## Part F — Deploy the order-service to ECS

Build the order-service container and push it. Then create a new ECS task definition and update the service.

### Step F.1 — ECR repository

**Console:** **ECR** → **Create repository** → `lab-order-service`, scan on push enabled → **Create**.

```bash
aws ecr create-repository \
  --repository-name lab-order-service \
  --image-scanning-configuration scanOnPush=true \
  --region eu-west-2
```

### Step F.2 — Build and push

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_HOST="${ACCOUNT_ID}.dkr.ecr.eu-west-2.amazonaws.com"

aws ecr get-login-password --region eu-west-2 \
  | docker login --username AWS --password-stdin $ECR_HOST

cd services/order-service && mvn package -DskipTests && cd ../..

docker build --platform linux/amd64 \
  -t ${ECR_HOST}/lab-order-service:latest \
  services/order-service/

docker push ${ECR_HOST}/lab-order-service:latest
```

### Step F.3 — Register new task definition

In the ECS Console, create a new task definition named `lab-order-service` using the same JSON format as Lab 2, but:
- Image: `<account>.dkr.ecr.eu-west-2.amazonaws.com/lab-order-service:latest`
- Add environment variables: `EVENTBRIDGE_BUS_NAME=lab-order-events`, `AWS_REGION=eu-west-2`
- Keep all DB env vars the same as Lab 2

### Step F.4 — Update or create ECS service

Update the existing ECS service to use the new task definition (or create a new service `lab-order-service` following the same steps as Lab 2, Part 7).

---

## Part G — Verify the full event flow

### Post an order

```bash
ALB_DNS=$(aws elbv2 describe-load-balancers \
  --names lab-alb \
  --query 'LoadBalancers[0].DNSName' --output text --region eu-west-2)

curl -X POST http://${ALB_DNS}/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"CUST-001","productId":1,"quantity":2}'

# Expected: 201 Created with order JSON
```

### Verify Lambda processed the event

**Console:** **CloudWatch → Log groups → /aws/lambda/lab-notification-consumer** → open the latest log stream. You should see:
```
ORDER NOTIFICATION — orderId=1 customerId=CUST-001 productId=1 quantity=2
```

**CLI:**
```bash
aws logs tail /aws/lambda/lab-notification-consumer --follow --region eu-west-2
```

### Check SQS metrics

**Console:** **SQS → lab-order-queue → Monitoring** tab → **Number of messages received** and **Number of messages deleted** both show a data point after the POST.

### Verify EventBridge routing

**Console:** **EventBridge → Event buses → lab-order-events → Monitoring** → **Matched events** shows a count of 1 after the POST.

---

## Part H — Demonstrate the DLQ

Send a malformed message directly to SQS to trigger Lambda failures:

**Console:** **SQS → lab-order-queue → Send and receive messages** → paste a non-JSON body like `bad-message` → **Send message**.

After 3 Lambda retry failures (watch Lambda logs for errors), the message appears in:

**Console:** **SQS → lab-order-dlq → Send and receive messages → Poll for messages**.

---

## Part I — Tear Down

### Remove event-driven resources (in order)

**Console / CLI:**
```bash
# 1. Delete Lambda trigger (event source mapping)
ESM_UUID=$(aws lambda list-event-source-mappings \
  --function-name lab-notification-consumer \
  --query 'EventSourceMappings[0].UUID' --output text --region eu-west-2)
aws lambda delete-event-source-mapping --uuid $ESM_UUID --region eu-west-2

# 2. Delete Lambda
aws lambda delete-function \
  --function-name lab-notification-consumer --region eu-west-2

# 3. Delete EventBridge rule and bus
aws events remove-targets \
  --rule lab-order-created-rule \
  --event-bus-name lab-order-events \
  --ids order-queue --region eu-west-2
aws events delete-rule \
  --name lab-order-created-rule \
  --event-bus-name lab-order-events --region eu-west-2
aws events delete-event-bus \
  --name lab-order-events --region eu-west-2

# 4. Delete SQS queues
aws sqs delete-queue --queue-url $QUEUE_URL --region eu-west-2
aws sqs delete-queue --queue-url $DLQ_URL --region eu-west-2

# 5. Delete ECR repo
aws ecr delete-repository \
  --repository-name lab-order-service --force --region eu-west-2

# 6. Delete IAM resources
aws iam delete-role-policy \
  --role-name lab-lambda-notification-role --policy-name SQSConsume
aws iam detach-role-policy \
  --role-name lab-lambda-notification-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
aws iam delete-role --role-name lab-lambda-notification-role

aws iam delete-role-policy \
  --role-name lab-ecs-task-role --policy-name EventBridgePutEvents

# 7. Delete CloudWatch log group
aws logs delete-log-group \
  --log-group-name /aws/lambda/lab-notification-consumer --region eu-west-2
```

**Console:** For each step, navigate to the corresponding console page and use the Delete/Remove action.

Then follow Lab 3 → Lab 2 → Lab 1 tear-down guides to remove the CI/CD, ECS, and VPC resources.

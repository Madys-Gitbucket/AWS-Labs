# Setup Guide — ECS Fargate Three-Tier Lab

This lab assumes you have already completed **Lab 1** (aws-three-tier-web-app-lab). The VPC, subnets, security groups, and RDS instance from Lab 1 are reused. Only the application tier changes.

If starting fresh, complete Parts 1–4 of the Lab 1 setup guide first to create the VPC and RDS, then return here.

---

## Prerequisites

Same as Lab 1, plus Docker:

```bash
java --version && mvn --version && aws --version && docker --version
aws sts get-caller-identity
```

---

## What this guide does

1. Create an ECR repository for the product-service image
2. Build the Docker image and push it to ECR
3. Create ECS cluster, task definition, and service
4. Register the ECS tasks with the existing ALB target group
5. Verify and tear down

---

## Part 1 — ECR Repository

### Step 1.1 — Create the repository

**Console:**
1. Go to **ECR** (Elastic Container Registry) → **Repositories** → **Create repository**.
2. Visibility: **Private**.
3. Repository name: `lab-product-service`
4. Image tag mutability: **Mutable** (for this lab — production should use Immutable).
5. Scan on push: **Enable** → this automatically scans each pushed image for vulnerabilities.
6. **Create repository**.

**CLI:**
```bash
aws ecr create-repository \
  --repository-name lab-product-service \
  --image-scanning-configuration scanOnPush=true \
  --region eu-west-2

# Add lifecycle policy — expire untagged images after 1 day
aws ecr put-lifecycle-policy \
  --repository-name lab-product-service \
  --lifecycle-policy-text '{
    "rules":[{"rulePriority":1,"description":"Expire untagged",
    "selection":{"tagStatus":"untagged","countType":"sinceImagePushed",
    "countUnit":"days","countNumber":1},"action":{"type":"expire"}}]}' \
  --region eu-west-2
```

### Step 1.2 — Build and push the image

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
REGION="eu-west-2"
ECR_HOST="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
REPO="${ECR_HOST}/lab-product-service"

# Authenticate Docker to ECR (token valid 12 hours)
aws ecr get-login-password --region $REGION \
  | docker login --username AWS --password-stdin $ECR_HOST
# Expected: Login Succeeded

# Build JAR
cd services/product-service && mvn package -DskipTests && cd ../..

# Build container image (linux/amd64 for Fargate x86_64)
docker build --platform linux/amd64 \
  -t "${REPO}:latest" \
  services/product-service/

# Push
docker push "${REPO}:latest"
```

**Console — verify push:**
- **ECR → Repositories → lab-product-service → Images** tab: image tagged `latest` appears with a push timestamp and scan status.

---

## Part 2 — IAM Roles for ECS

### Step 2.1 — Task Execution Role

The execution role is used by the **ECS agent** (not your application) to pull the image from ECR and write logs to CloudWatch.

**Console:**
1. **IAM** → **Roles** → **Create role**.
2. Trusted entity: **AWS service** → Use case: **Elastic Container Service Task** → **Next**.
3. Attach policy: `AmazonECSTaskExecutionRolePolicy` (search for it).
4. Role name: `lab-ecs-execution-role` → **Create role**.

**CLI:**
```bash
aws iam create-role \
  --role-name lab-ecs-execution-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow",
    "Principal":{"Service":"ecs-tasks.amazonaws.com"},
    "Action":"sts:AssumeRole"}]}'

aws iam attach-role-policy \
  --role-name lab-ecs-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

### Step 2.2 — Task Role

The task role is assumed by your **application code**. For this lab it has no permissions (the app only talks to RDS via JDBC). It is a placeholder for future use.

**Console:** Create another role:
- Trusted entity: **Elastic Container Service Task**
- No policies attached
- Role name: `lab-ecs-task-role`

**CLI:**
```bash
aws iam create-role \
  --role-name lab-ecs-task-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow",
    "Principal":{"Service":"ecs-tasks.amazonaws.com"},
    "Action":"sts:AssumeRole"}]}'
```

---

## Part 3 — ECS Cluster

**Console:**
1. **ECS** → **Clusters** → **Create cluster**.
2. Cluster name: `lab-cluster`
3. Infrastructure: **AWS Fargate (serverless)** — untick EC2.
4. Monitoring: tick **Use Container Insights** (enables cluster-level CloudWatch metrics).
5. **Create**.

**CLI:**
```bash
aws ecs create-cluster \
  --cluster-name lab-cluster \
  --settings name=containerInsights,value=enabled \
  --region eu-west-2
```

---

## Part 4 — CloudWatch Log Group

**Console:** **CloudWatch** → **Log groups** → **Create log group** → Name: `/ecs/lab-product-service` → Retention: **7 days** → **Create**.

**CLI:**
```bash
aws logs create-log-group \
  --log-group-name /ecs/lab-product-service \
  --region eu-west-2

aws logs put-retention-policy \
  --log-group-name /ecs/lab-product-service \
  --retention-in-days 7 \
  --region eu-west-2
```

---

## Part 5 — Task Definition

The task definition is the blueprint for your container. You register a new revision every time you want to change the image, environment variables, or resources.

**Console:**
1. **ECS** → **Task definitions** → **Create new task definition** → **Create new task definition with JSON** (easiest for this amount of config).
2. Paste the JSON below, then click **Create**. Replace the placeholder values first.

```json
{
  "family": "lab-product-service",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "256",
  "memory": "512",
  "executionRoleArn": "arn:aws:iam::<ACCOUNT_ID>:role/lab-ecs-execution-role",
  "taskRoleArn": "arn:aws:iam::<ACCOUNT_ID>:role/lab-ecs-task-role",
  "containerDefinitions": [
    {
      "name": "product-service",
      "image": "<ACCOUNT_ID>.dkr.ecr.eu-west-2.amazonaws.com/lab-product-service:latest",
      "essential": true,
      "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
      "environment": [
        {"name": "DB_HOST",     "value": "<RDS_ENDPOINT>"},
        {"name": "DB_PORT",     "value": "3306"},
        {"name": "DB_NAME",     "value": "webapp"},
        {"name": "DB_USER",     "value": "webapp"},
        {"name": "DB_PASSWORD", "value": "<YOUR_DB_PASSWORD>"}
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group":         "/ecs/lab-product-service",
          "awslogs-region":        "eu-west-2",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

> **Note:** Putting the DB password in a task definition environment variable is acceptable for a lab. In production, use Secrets Manager and reference it via `secrets` in the container definition instead of `environment`. See `docs/production-considerations.md`.

**CLI:**
```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
RDS_ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier lab-mysql \
  --query 'DBInstances[0].Endpoint.Address' \
  --output text --region eu-west-2)

aws ecs register-task-definition \
  --family lab-product-service \
  --network-mode awsvpc \
  --requires-compatibilities FARGATE \
  --cpu 256 --memory 512 \
  --execution-role-arn "arn:aws:iam::${ACCOUNT_ID}:role/lab-ecs-execution-role" \
  --task-role-arn "arn:aws:iam::${ACCOUNT_ID}:role/lab-ecs-task-role" \
  --container-definitions "[{
    \"name\":\"product-service\",
    \"image\":\"${ACCOUNT_ID}.dkr.ecr.eu-west-2.amazonaws.com/lab-product-service:latest\",
    \"essential\":true,
    \"portMappings\":[{\"containerPort\":8080,\"protocol\":\"tcp\"}],
    \"environment\":[
      {\"name\":\"DB_HOST\",\"value\":\"${RDS_ENDPOINT}\"},
      {\"name\":\"DB_PORT\",\"value\":\"3306\"},
      {\"name\":\"DB_NAME\",\"value\":\"webapp\"},
      {\"name\":\"DB_USER\",\"value\":\"webapp\"},
      {\"name\":\"DB_PASSWORD\",\"value\":\"<YOUR_DB_PASSWORD>\"}
    ],
    \"logConfiguration\":{
      \"logDriver\":\"awslogs\",
      \"options\":{
        \"awslogs-group\":\"/ecs/lab-product-service\",
        \"awslogs-region\":\"eu-west-2\",
        \"awslogs-stream-prefix\":\"ecs\"
      }
    }
  }]" \
  --region eu-west-2
```

---

## Part 6 — ALB Target Group (type=ip)

ECS Fargate tasks use `awsvpc` networking — each task gets its own ENI and private IP. The target group must use `type=ip` (not `instance` as in Lab 1).

**Console:**
1. **EC2** → **Target groups** → **Create target group**.
2. Target type: **IP addresses** (not Instances).
3. Name: `lab-product-tg`
4. Protocol: HTTP, Port: **8080**
5. VPC: `lab-vpc`
6. Health check path: `/actuator/health`
7. Healthy threshold: 2, Interval: 30s
8. **Next** → skip adding targets manually (ECS service will register them automatically) → **Create target group**.

**CLI:**
```bash
VPC_ID=$(aws ec2 describe-vpcs \
  --filters "Name=tag:Name,Value=lab-vpc" \
  --query 'Vpcs[0].VpcId' --output text --region eu-west-2)

TG_ARN=$(aws elbv2 create-target-group \
  --name lab-product-tg \
  --protocol HTTP --port 8080 \
  --vpc-id $VPC_ID \
  --target-type ip \
  --health-check-path /actuator/health \
  --healthy-threshold-count 2 \
  --health-check-interval-seconds 30 \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text --region eu-west-2)
echo "Target group: $TG_ARN"
```

### Update the ALB listener

Point the existing ALB's HTTP listener to the new target group.

**Console:**
1. **EC2** → **Load Balancers** → `lab-alb` → **Listeners** tab.
2. Click the **HTTP:80** listener → **Edit listener**.
3. Default action: change **Forward to** from the old Lab 1 target group to `lab-product-tg` → **Save changes**.

**CLI:**
```bash
ALB_ARN=$(aws elbv2 describe-load-balancers \
  --names lab-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text --region eu-west-2)

LISTENER_ARN=$(aws elbv2 describe-listeners \
  --load-balancer-arn $ALB_ARN \
  --query 'Listeners[0].ListenerArn' --output text --region eu-west-2)

aws elbv2 modify-listener \
  --listener-arn $LISTENER_ARN \
  --default-actions Type=forward,TargetGroupArn=$TG_ARN \
  --region eu-west-2
```

---

## Part 7 — ECS Service

The ECS service maintains the desired number of running tasks and keeps them registered with the target group.

You need the private app subnet IDs and the app security group ID from Lab 1.

**Console:**
1. **ECS** → **Clusters** → `lab-cluster` → **Services** tab → **Create**.
2. Launch type: **FARGATE**.
3. Task definition: `lab-product-service` (latest revision).
4. Service name: `lab-product-service`
5. Desired tasks: **2**
6. Networking:
   - VPC: `lab-vpc`
   - Subnets: select **lab-private-app-a** and **lab-private-app-b**
   - Security group: select existing `lab-app-sg`
   - Public IP: **Turned off** (tasks are in private subnets)
7. Load balancing:
   - Load balancer type: **Application Load Balancer**
   - Select existing: `lab-alb`
   - Container to load balance: `product-service:8080`
   - Listener: select existing HTTP:80
   - Target group: select existing `lab-product-tg`
8. **Create**.

**CLI:**
```bash
APP_SG=$(aws ec2 describe-security-groups \
  --filters "Name=group-name,Values=lab-app-sg" \
  --query 'SecurityGroups[0].GroupId' --output text --region eu-west-2)

APP_SUBNET_A=$(aws ec2 describe-subnets \
  --filters "Name=tag:Name,Values=lab-private-app-a" \
  --query 'Subnets[0].SubnetId' --output text --region eu-west-2)
APP_SUBNET_B=$(aws ec2 describe-subnets \
  --filters "Name=tag:Name,Values=lab-private-app-b" \
  --query 'Subnets[0].SubnetId' --output text --region eu-west-2)

aws ecs create-service \
  --cluster lab-cluster \
  --service-name lab-product-service \
  --task-definition lab-product-service \
  --desired-count 2 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={
    subnets=[${APP_SUBNET_A},${APP_SUBNET_B}],
    securityGroups=[${APP_SG}],
    assignPublicIp=DISABLED}" \
  --load-balancers "targetGroupArn=${TG_ARN},containerName=product-service,containerPort=8080" \
  --region eu-west-2

# Wait for stable
aws ecs wait services-stable \
  --cluster lab-cluster --services lab-product-service --region eu-west-2
echo "Service stable"
```

---

## Part 8 — Verify

### Check ECS tasks are running

**Console:** **ECS → Clusters → lab-cluster → Tasks** tab. Both tasks should show **RUNNING**.

**CLI:**
```bash
aws ecs list-tasks \
  --cluster lab-cluster \
  --service-name lab-product-service \
  --region eu-west-2
```

### Check ALB target health

**Console:** **EC2 → Target groups → lab-product-tg → Targets** tab. Both IPs should show **healthy** within ~90 seconds of the tasks starting.

```bash
aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --query 'TargetHealthDescriptions[*].[Target.Id,TargetHealth.State]' \
  --output table --region eu-west-2
```

### Test the API

```bash
ALB_DNS=$(aws elbv2 describe-load-balancers \
  --names lab-alb \
  --query 'LoadBalancers[0].DNSName' --output text --region eu-west-2)

curl http://${ALB_DNS}/actuator/health
curl http://${ALB_DNS}/products
curl http://${ALB_DNS}/products/1
```

### Check container logs

**Console:** **CloudWatch → Log groups → /ecs/lab-product-service** → open a log stream to see Spring Boot output.

```bash
aws logs tail /ecs/lab-product-service --follow --region eu-west-2
```

---

## Part 9 — Manual Redeploy (change → build → push → deploy)

This is the manual cycle that Lab 3 will automate:

1. Make a code change in `services/product-service/`
2. `mvn package -DskipTests`
3. `docker build --platform linux/amd64 -t ${REPO}:latest services/product-service/`
4. `docker push ${REPO}:latest`
5. Force new deployment:
   - **Console:** ECS → Clusters → lab-cluster → Services → `lab-product-service` → **Update** → tick **Force new deployment** → **Update**.
   - **CLI:** `aws ecs update-service --cluster lab-cluster --service lab-product-service --force-new-deployment --region eu-west-2`
6. Watch the deployment in the **Deployments** tab — old tasks drain, new tasks start.

---

## Part 10 — Tear Down (ECS layer only)

If keeping Lab 1 VPC and RDS running, only delete the ECS resources:

**Console:**
1. **ECS → Clusters → lab-cluster → Services** → select `lab-product-service` → **Delete** → confirm.
2. Wait for service to be deleted.
3. **ECS → Clusters** → select `lab-cluster` → **Delete cluster** → confirm.
4. **EC2 → Target groups** → select `lab-product-tg` → **Delete**.
5. **ECR → Repositories** → `lab-product-service` → **Delete repository** (tick "Delete all images inside").
6. **IAM → Roles** → delete `lab-ecs-execution-role` and `lab-ecs-task-role`.
7. **CloudWatch → Log groups** → delete `/ecs/lab-product-service`.

**CLI:**
```bash
aws ecs update-service \
  --cluster lab-cluster --service lab-product-service \
  --desired-count 0 --region eu-west-2
aws ecs delete-service \
  --cluster lab-cluster --service lab-product-service \
  --force --region eu-west-2
aws ecs delete-cluster --cluster lab-cluster --region eu-west-2

aws elbv2 delete-target-group --target-group-arn $TG_ARN --region eu-west-2

aws ecr delete-repository \
  --repository-name lab-product-service --force --region eu-west-2

aws iam detach-role-policy \
  --role-name lab-ecs-execution-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
aws iam delete-role --role-name lab-ecs-execution-role
aws iam delete-role --role-name lab-ecs-task-role

aws logs delete-log-group \
  --log-group-name /ecs/lab-product-service --region eu-west-2
```

To tear down everything (VPC + RDS too), follow **Part 9** of the Lab 1 setup guide.

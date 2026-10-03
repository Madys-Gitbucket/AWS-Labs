# Architecture: ECS Fargate Three-Tier

## What changed from Lab 1

EC2 instances are replaced by ECS Fargate tasks. The VPC, ALB, RDS, and subnet layout are identical — only the **application tier** changes.

```
Lab 1:  ALB → EC2 (systemd, JAR on disk) → RDS
Lab 2:  ALB → ECS Fargate tasks (container, ECR image) → RDS
```

## Infrastructure Diagram

```mermaid
graph TB
    subgraph Internet
        Client[Browser / curl]
    end

    subgraph AWS["AWS (eu-west-2)"]
        subgraph VPC["VPC 10.0.0.0/16"]
            subgraph Public["Public Subnets (AZ-a, AZ-b)"]
                ALB[ALB :80]
                NAT[NAT Gateway]
            end
            subgraph PrivateApp["Private App Subnets (AZ-a, AZ-b)"]
                Task1[Fargate Task\nproduct-service\n:8080]
                Task2[Fargate Task\nproduct-service\n:8080]
            end
            subgraph PrivateData["Private Data Subnets (AZ-a, AZ-b)"]
                RDS[RDS MySQL 8.0]
            end
        end
        ECR[ECR Repository\nlab-product-service]
        CW[CloudWatch Logs\n/ecs/lab-product-service]
    end

    Client --> ALB
    ALB --> Task1 & Task2
    Task1 & Task2 --> RDS
    Task1 & Task2 -.->|pull image| ECR
    Task1 & Task2 --> CW
```

## Key Differences vs Lab 1 (EC2)

| Aspect | Lab 1 EC2 | Lab 2 ECS Fargate |
|--------|-----------|-------------------|
| Deployment unit | JAR file on disk | Docker image in ECR |
| Scaling | Manual: launch new instance | `desired_count` in ECS service |
| Patching OS | Manual yum update | New image build replaces container |
| Networking | ENI on EC2 instance | ENI per task (awsvpc mode) |
| Target group type | `instance` (EC2 instance ID) | `ip` (task private IP) |
| Config injection | env file `/opt/webapp/env.conf` | ECS task definition environment variables |
| Logs | CloudWatch Agent on EC2 | awslogs driver in container |

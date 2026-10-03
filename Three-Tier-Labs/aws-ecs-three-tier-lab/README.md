# ECS Fargate Three-Tier Lab

Extension of Lab 1. The VPC, ALB, and RDS remain unchanged. The EC2 application tier is replaced by **Amazon ECS Fargate** — the Spring Boot app runs as a Docker container pulled from ECR.

Full setup guide: [docs/setup-guide.md](docs/setup-guide.md)

---

## What changes from Lab 1

```
Lab 1:  Internet → ALB → EC2 (private subnet) → RDS
Lab 2:  Internet → ALB → ECS Fargate tasks (private subnet) → RDS
                              ↑
                       Image pulled from ECR
```

Same VPC. Same ALB. Same RDS. Application tier is containerised.

---

## New concepts introduced

| Concept | Detail |
|---------|--------|
| ECR | Container image repository — scan on push, lifecycle policy |
| ECS Cluster | Logical grouping of Fargate tasks |
| Task Definition | Blueprint: image URI, CPU/memory, env vars, log config, IAM roles |
| ECS Service | Maintains desired task count, registers tasks with ALB target group |
| awsvpc networking | Each task gets its own ENI and private IP — target type must be `ip` |
| Task execution role | IAM role the ECS agent uses to pull from ECR and write to CloudWatch |
| Task role | IAM role the application code uses (e.g. to call other AWS services) |

---

## Lab Series

| Lab | Repo | Focus |
|-----|------|-------|
| **Lab 1** | aws-three-tier-web-app-lab | VPC + EC2 + RDS |
| **Lab 2 (this)** | aws-ecs-three-tier-lab | Containerise onto ECS Fargate |
| **Lab 3** | aws-ecs-cicd-three-tier-lab | GitHub Actions CI/CD |
| **Lab 4** | aws-event-driven-three-tier-lab | EventBridge + SQS async events |

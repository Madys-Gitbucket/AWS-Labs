# Three-Tier Web App on AWS

A hands-on lab building the classic three-tier architecture: Application Load Balancer in public subnets, EC2 application servers in private subnets, and RDS MySQL in isolated data subnets — all inside a custom VPC.

Full step-by-step setup (AWS Console + CLI): [docs/setup-guide.md](docs/setup-guide.md)

---

## Architecture

```
Internet
   │  HTTP :80
   ▼
Application Load Balancer          ← public subnets (AZ-a, AZ-b)
   │  port 8080  (round-robin)
   ├──▶ EC2 lab-app-a (AZ-a)       ← private app subnets
   └──▶ EC2 lab-app-b (AZ-b)         Spring Boot, Java 21
              │  JDBC :3306
              ▼
         RDS MySQL 8.0             ← private data subnets (AZ-a, AZ-b)
```

Each tier lives in its own subnet layer and is isolated by security group rules — the internet can only reach the ALB, the ALB can only reach EC2 on port 8080, and EC2 can only reach RDS on port 3306.

See [architecture/README.md](architecture/README.md) for full Mermaid diagrams.

---

## What this lab covers

| Topic | Detail |
|-------|--------|
| Custom VPC | 6 subnets across 2 AZs, IGW, NAT Gateway, route tables |
| Security Groups | Layered rules using SG-to-SG references (not CIDR) |
| ALB | Health checks, target group, internet-facing listener |
| EC2 | Private subnet, IAM instance profile, systemd service, user data bootstrap |
| RDS | MySQL 8.0, private subnet, DB subnet group, no public access |
| SSM | Session Manager for shell access — no SSH, no key pairs, no bastion |
| CloudWatch | Log group, agent on EC2, log streams per instance |
| Deployment | S3 + SSM Run Command — no direct instance access needed |

---

## Application

Spring Boot 3.3 REST API backed by MySQL:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/health` | GET | Returns `{"status":"UP"}` — used by ALB health check |
| `/products` | GET | List all products (optional `?category=` filter) |
| `/products/{id}` | GET | Get product by ID — 404 if not found |
| `/products` | POST | Create a product — returns 201 |

---

## Quick Start

```bash
# 1. Build and test locally
cd application && mvn test && mvn package -DskipTests && cd ..

# 2. Follow the Console setup guide for all AWS resources
# docs/setup-guide.md — Parts 1 through 7

# 3. Test
curl http://<alb-dns>/health
curl http://<alb-dns>/products

# 4. Tear down when done (important — RDS and NAT cost money at rest)
# docs/setup-guide.md — Part 9
```

---

## Lab Series — Extension Path

This lab is the foundation. Each subsequent lab extends the same three-tier system:

| Lab | Repo | What changes |
|-----|------|-------------|
| **Lab 1 (this)** | aws-three-tier-web-app-lab | VPC + EC2 + RDS, manual deployment |
| **Lab 2** | aws-ecs-three-tier-lab | Replace EC2 with ECS Fargate; containerise the app |
| **Lab 3** | aws-ecs-cicd-three-tier-lab | Add GitHub Actions CI/CD pipeline with GitHub OIDC |
| **Lab 4** | aws-event-driven-three-tier-lab | Add EventBridge + SQS + Lambda for async order events |

---

## MVP vs Production

See [docs/production-considerations.md](docs/production-considerations.md) for a full table. Key gaps in this lab:

- No HTTPS (ACM + ALB listener redirect)
- No Auto Scaling (fixed 2 EC2 instances)
- Single-AZ NAT Gateway
- Credentials in env file (vs Secrets Manager)
- No WAF

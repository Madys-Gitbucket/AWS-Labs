# Architecture: Three-Tier Web App on AWS

## Overview

A classic three-tier architecture: presentation (ALB), application (EC2), and data (RDS MySQL) — each tier in its own subnet layer, isolated by security groups.

## Infrastructure Diagram

```mermaid
graph TB
    subgraph Internet
        Client[Browser / curl]
    end

    subgraph AWS["AWS (eu-west-2)"]
        subgraph VPC["VPC — 10.0.0.0/16"]

            subgraph PublicSubnets["Public Subnets (AZ-a 10.0.1.0/24, AZ-b 10.0.2.0/24)"]
                ALB[Application Load Balancer\nHTTP :80]
                BastionNote["(No bastion — SSM only)"]
            end

            subgraph PrivateAppSubnets["Private App Subnets (AZ-a 10.0.3.0/24, AZ-b 10.0.4.0/24)"]
                EC2a[EC2 t3.small\nAZ-a\nSpring Boot :8080]
                EC2b[EC2 t3.small\nAZ-b\nSpring Boot :8080]
            end

            subgraph PrivateDataSubnets["Private Data Subnets (AZ-a 10.0.5.0/24, AZ-b 10.0.6.0/24)"]
                RDS[RDS MySQL 8.0\nMulti-AZ Standby\n:3306]
            end

            NAT[NAT Gateway\nin Public Subnet AZ-a]
        end

        subgraph Supporting
            CW[CloudWatch Logs\n/ec2/webapp]
            SSM[SSM Session Manager\nno SSH needed]
        end
    end

    Client -->|HTTP| ALB
    ALB -->|port 8080| EC2a
    ALB -->|port 8080| EC2b
    EC2a -->|JDBC :3306| RDS
    EC2b -->|JDBC :3306| RDS
    EC2a -.->|outbound via| NAT
    EC2b -.->|outbound via| NAT
    EC2a --> CW
    EC2b --> CW
    EC2a -.- SSM
    EC2b -.- SSM
```

## Security Group Rules

```
ALB Security Group (lab-alb-sg)
  Inbound:  0.0.0.0/0  → TCP 80
  Outbound: all

App Security Group (lab-app-sg)
  Inbound:  lab-alb-sg → TCP 8080   (ALB only, not the internet)
  Outbound: all

DB Security Group (lab-db-sg)
  Inbound:  lab-app-sg → TCP 3306   (app tier only)
  Outbound: all
```

## Request Flow

```mermaid
sequenceDiagram
    participant Client
    participant ALB
    participant EC2 as EC2 (app tier)
    participant RDS as RDS MySQL

    Client->>ALB: GET /products
    ALB->>EC2: Forward (round-robin)
    EC2->>RDS: SELECT * FROM products
    RDS-->>EC2: Result set
    EC2-->>ALB: JSON response
    ALB-->>Client: HTTP 200 + JSON
```

## Tier Responsibilities

| Tier | Component | Responsibility |
|------|-----------|----------------|
| Presentation | ALB | Receives internet traffic, health checks, distributes to app tier |
| Application | EC2 + Spring Boot | Business logic, JDBC reads/writes, exposes REST API |
| Data | RDS MySQL | Persistent storage, transactions, backups |

## Subnet Design

| Subnet | CIDR | Purpose |
|--------|------|---------|
| public-a | 10.0.1.0/24 | ALB node, NAT Gateway |
| public-b | 10.0.2.0/24 | ALB node (second AZ required) |
| private-app-a | 10.0.3.0/24 | EC2 app instances AZ-a |
| private-app-b | 10.0.4.0/24 | EC2 app instances AZ-b |
| private-data-a | 10.0.5.0/24 | RDS primary |
| private-data-b | 10.0.6.0/24 | RDS standby (Multi-AZ) |

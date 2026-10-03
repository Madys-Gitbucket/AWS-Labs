# Architecture Decisions

## ADR-001: Three-Tier Subnet Layout

**Decision:** Six subnets across two AZs — two public (ALB + NAT), two private-app (EC2), two private-data (RDS).

**Rationale:**
- ALB requires two subnets in different AZs.
- EC2 in private subnets means the instances have no public IP — they cannot be reached directly from the internet. The only inbound path is through the ALB.
- RDS in a separate private layer means the database is only reachable from the app subnet — not from the ALB, not from the internet.
- Separation of concerns: each tier can have its own routing, NACLs, and security group rules without affecting the others.

---

## ADR-002: NAT Gateway for Outbound Traffic from Private Subnets

**Decision:** One NAT Gateway in public-a. Private app subnets route 0.0.0.0/0 through it.

**Rationale:** EC2 instances in private subnets need outbound internet access to: download Java packages during bootstrap, reach SSM endpoints, and send logs to CloudWatch. NAT Gateway is the managed solution — no EC2 NAT instance to maintain.

**MVP trade-off:** One NAT Gateway (single AZ) saves cost. Production should have one per AZ for HA.

---

## ADR-003: RDS MySQL over Self-Managed MySQL on EC2

**Decision:** Use Amazon RDS MySQL 8.0.

**Rationale:** RDS handles automated backups, patch management, Multi-AZ failover, and parameter group management. Running MySQL on EC2 requires manual work for all of these. For a lab focused on architecture, the managed service removes undifferentiated heavy lifting.

---

## ADR-004: ALB over NLB

**Decision:** Application Load Balancer.

**Rationale:** ALB operates at Layer 7. It supports HTTP health checks (checking `/health` endpoint response body, not just TCP connection), path-based routing (useful in Lab 2 when the app splits into microservices), and HTTPS termination. NLB is Layer 4 — appropriate for TCP/TLS passthrough, but over-specified for HTTP workloads.

---

## ADR-005: SSM Session Manager over SSH Bastion

**Decision:** No bastion host, no SSH inbound rules. Use SSM Session Manager for shell access.

**Rationale:** A bastion host adds another EC2 instance to maintain, patch, and secure. It introduces an open inbound SSH rule that must be tightly controlled. SSM Session Manager provides audited shell access through the SSM endpoint without any inbound firewall rules. The EC2 IAM role grants the `AmazonSSMManagedInstanceCore` permission; no key pair is required.

---

## ADR-006: Single EC2 Instance Per AZ (Two Total)

**Decision:** Two EC2 instances — one per private-app subnet — both registered with the ALB target group.

**Rationale:** Running across two AZs protects against a single AZ failure. The ALB health check removes a failed instance from rotation automatically. Two instances also show the ALB round-robin behaviour clearly during the lab.

---

## ADR-007: DB Credentials via Environment File (not SSM Parameter Store)

**Decision:** Credentials stored in `/opt/webapp/env.conf` on the EC2 instance, loaded by systemd `EnvironmentFile`.

**Rationale:** Simpler for a first lab. The env file is `chmod 600`, owned by the webapp service user. It demonstrates the concept of externalising configuration from the application JAR.

**Production alternative:** Store credentials in AWS Secrets Manager. Retrieve at startup via the Secrets Manager SDK. Rotate automatically. See `docs/production-considerations.md`.

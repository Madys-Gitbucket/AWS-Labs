# Production Considerations

| Area | This Lab (MVP) | Production |
|------|---------------|------------|
| **EC2 count** | 2 instances (one per AZ), fixed | Auto Scaling Group: min 2, scale on CPU/request count; EC2 Launch Template |
| **EC2 size** | t3.small | Right-sized based on load testing; reserved or savings plan pricing |
| **NAT Gateway** | 1 (single AZ) | 1 per AZ for HA; or VPC endpoints for AWS services (SSM, CloudWatch, S3) to reduce NAT cost |
| **RDS** | Single-AZ (lab cost saving) | Multi-AZ enabled; read replica for reporting workloads |
| **RDS instance** | db.t3.micro | Right-sized; use Performance Insights to tune |
| **Database credentials** | env file on EC2 | AWS Secrets Manager; automatic rotation; app retrieves at startup |
| **HTTPS** | HTTP only | ACM certificate on ALB; HTTP → HTTPS redirect listener rule; HSTS header |
| **EC2 AMI updates** | Manual re-deploy | Golden AMI pipeline: Packer builds patched AMI → Auto Scaling Group rolling refresh |
| **Deployment** | Manual JAR copy via SSM | CI/CD pipeline (see Lab 3); Blue/Green deployment via CodeDeploy |
| **Monitoring** | CloudWatch Logs only | CloudWatch Container Insights; application metrics (Spring Boot Actuator → CloudWatch); alarms on error rate, p99 latency, DB connections |
| **Secrets** | Plaintext env file | Secrets Manager; KMS-encrypted at rest |
| **WAF** | None | AWS WAF on ALB: rate limiting, managed rule groups (OWASP Top 10), bot control |
| **Access control** | AdministratorAccess IAM user | Least-privilege IAM roles per component; SCPs at AWS Organizations level |
| **Backup** | RDS automated backups (7 days default) | Longer retention; test restore procedure quarterly; cross-region copy |
| **Multi-region** | Single region | Route 53 health checks + failover routing to standby region; Aurora Global Database |
| **Cost** | ~$3/day running (EC2 + RDS + NAT) | Reserved instances for baseline; Savings Plans; Cost Anomaly Detection alerts |

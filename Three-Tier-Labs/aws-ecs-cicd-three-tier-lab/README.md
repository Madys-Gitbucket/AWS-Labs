# ECS + CI/CD Three-Tier Lab

Extension of Labs 1 and 2. The VPC, ALB, RDS, and ECS cluster remain unchanged. This lab adds a **GitHub Actions CI/CD pipeline** that automatically builds, tests, pushes, and deploys the product-service on every push to `main`.

Full setup guide: [docs/setup-guide.md](docs/setup-guide.md)

---

## What changes from Lab 2

```
Lab 2:  git push → manual: mvn → docker build → docker push → aws ecs update-service
Lab 3:  git push → GitHub Actions → ci job → cd job → ECS deployed automatically
```

---

## Pipeline overview

```
git push to main
      │
      ▼
   ci job
   ├── Compile
   ├── Unit tests
   ├── Package JAR
   └── Docker build (verify only)
      │ (only if ci passes)
      ▼
   cd job
   ├── OIDC → assume AWS deploy role (no stored keys)
   ├── ECR login
   ├── Build + push image (SHA tag + latest)
   ├── Render new task definition
   ├── Deploy to ECS (wait for stability)
   └── Smoke test ALB health endpoint
```

---

## Key concepts

| Concept | Detail |
|---------|--------|
| GitHub OIDC | GitHub gets a short-lived token; AWS trusts GitHub as an identity provider — no `AWS_ACCESS_KEY_ID` stored in GitHub |
| IAM deploy role | Least-privilege role: ECR push + ECS deploy + IAM PassRole only |
| Immutable image tag | Each image tagged with `github.sha` — every deployment is traceable to a commit |
| CD gate | `cd` job only runs on push to `main`, never on pull requests |
| Smoke test | Fails the pipeline if the ALB health endpoint returns non-200 after deploy |

---

## Lab Series

| Lab | Repo | Focus |
|-----|------|-------|
| Lab 1 | aws-three-tier-web-app-lab | VPC + EC2 + RDS |
| Lab 2 | aws-ecs-three-tier-lab | ECS Fargate |
| **Lab 3 (this)** | aws-ecs-cicd-three-tier-lab | GitHub Actions CI/CD |
| Lab 4 | aws-event-driven-three-tier-lab | EventBridge + SQS async events |

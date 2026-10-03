# Setup Guide — ECS + CI/CD Three-Tier Lab

This lab assumes Labs 1 and 2 are complete: the VPC, RDS, ALB, ECS cluster, and ECR repository already exist. This guide adds the GitHub Actions pipeline and the AWS IAM resources it needs.

---

## Prerequisites

- Labs 1 and 2 completed (VPC, RDS, ECS cluster, ECR repo all exist)
- GitHub account with this repository pushed to it
- GitHub CLI (`gh`) — optional but recommended

---

## Part A — AWS: Create the GitHub OIDC Identity Provider

GitHub OIDC lets GitHub Actions prove its identity to AWS without storing any long-lived credentials. AWS trusts GitHub's token issuer and exchanges the short-lived OIDC token for temporary AWS credentials.

This provider can only exist **once per AWS account**. Check first.

**Console — check:**
1. Go to **IAM** → **Identity providers** (left sidebar).
2. Look for `token.actions.githubusercontent.com`.

**CLI — check:**
```bash
aws iam list-open-id-connect-providers \
  --query 'OpenIDConnectProviderList[*].Arn' \
  --output table
```

### If it does NOT exist — create it

**Console:**
1. **IAM** → **Identity providers** → **Add provider**.
2. Provider type: **OpenID Connect**.
3. Provider URL: `https://token.actions.githubusercontent.com`
4. Click **Get thumbprint** — AWS fetches the certificate thumbprint automatically.
5. Audience: `sts.amazonaws.com`
6. **Add provider**.

**CLI:**
```bash
aws iam create-open-id-connect-provider \
  --url https://token.actions.githubusercontent.com \
  --client-id-list sts.amazonaws.com \
  --thumbprint-list 6938fd4d98bab03faadb97b34396831e3780aea1
```

---

## Part B — AWS: Create the IAM Deploy Role

This is the role GitHub Actions assumes. It must be scoped narrowly — only the permissions needed to push to ECR and deploy to ECS.

### Step B.1 — Create the role with a trust policy

Replace `YOUR_GITHUB_USERNAME` and `aws-ecs-cicd-three-tier-lab` with your actual values.

**Console:**
1. **IAM** → **Roles** → **Create role**.
2. Trusted entity: **Web identity**.
3. Identity provider: select `token.actions.githubusercontent.com`.
4. Audience: `sts.amazonaws.com`.
5. GitHub organisation: `YOUR_GITHUB_USERNAME`
6. GitHub repository: `aws-ecs-cicd-three-tier-lab`
7. Click **Next** (we'll add permissions in the next step).
8. Skip adding managed policies for now — we'll use an inline policy.
9. Role name: `lab-github-deploy-role` → **Create role**.

**CLI:**
```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
GITHUB_USER="YOUR_GITHUB_USERNAME"
GITHUB_REPO="aws-ecs-cicd-three-tier-lab"

aws iam create-role \
  --role-name lab-github-deploy-role \
  --assume-role-policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [{
      \"Effect\": \"Allow\",
      \"Principal\": {\"Federated\": \"arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com\"},
      \"Action\": \"sts:AssumeRoleWithWebIdentity\",
      \"Condition\": {
        \"StringEquals\": {
          \"token.actions.githubusercontent.com:aud\": \"sts.amazonaws.com\"
        },
        \"StringLike\": {
          \"token.actions.githubusercontent.com:sub\": \"repo:${GITHUB_USER}/${GITHUB_REPO}:*\"
        }
      }
    }]
  }"
```

### Step B.2 — Attach the deploy permissions (inline policy)

**Console:**
1. Open `lab-github-deploy-role` → **Permissions** tab → **Add permissions** → **Create inline policy**.
2. Switch to **JSON** editor and paste this, replacing `<ACCOUNT_ID>`:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ECRAuth",
      "Effect": "Allow",
      "Action": "ecr:GetAuthorizationToken",
      "Resource": "*"
    },
    {
      "Sid": "ECRPush",
      "Effect": "Allow",
      "Action": [
        "ecr:BatchCheckLayerAvailability",
        "ecr:PutImage",
        "ecr:InitiateLayerUpload",
        "ecr:UploadLayerPart",
        "ecr:CompleteLayerUpload",
        "ecr:DescribeImages"
      ],
      "Resource": "arn:aws:ecr:eu-west-2:<ACCOUNT_ID>:repository/lab-product-service"
    },
    {
      "Sid": "ECSDescribe",
      "Effect": "Allow",
      "Action": [
        "ecs:DescribeTaskDefinition",
        "ecs:DescribeServices"
      ],
      "Resource": "*"
    },
    {
      "Sid": "ECSDeploy",
      "Effect": "Allow",
      "Action": [
        "ecs:RegisterTaskDefinition",
        "ecs:UpdateService"
      ],
      "Resource": [
        "arn:aws:ecs:eu-west-2:<ACCOUNT_ID>:cluster/lab-cluster",
        "arn:aws:ecs:eu-west-2:<ACCOUNT_ID>:service/lab-cluster/lab-product-service",
        "arn:aws:ecs:eu-west-2:<ACCOUNT_ID>:task-definition/lab-product-service:*"
      ]
    },
    {
      "Sid": "IAMPassRole",
      "Effect": "Allow",
      "Action": "iam:PassRole",
      "Resource": [
        "arn:aws:iam::<ACCOUNT_ID>:role/lab-ecs-execution-role",
        "arn:aws:iam::<ACCOUNT_ID>:role/lab-ecs-task-role"
      ]
    }
  ]
}
```

3. Policy name: `lab-github-deploy-policy` → **Create policy**.

**CLI:**
```bash
aws iam put-role-policy \
  --role-name lab-github-deploy-role \
  --policy-name lab-github-deploy-policy \
  --policy-document "{
    \"Version\": \"2012-10-17\",
    \"Statement\": [
      {\"Sid\":\"ECRAuth\",\"Effect\":\"Allow\",
       \"Action\":\"ecr:GetAuthorizationToken\",\"Resource\":\"*\"},
      {\"Sid\":\"ECRPush\",\"Effect\":\"Allow\",
       \"Action\":[\"ecr:BatchCheckLayerAvailability\",\"ecr:PutImage\",
         \"ecr:InitiateLayerUpload\",\"ecr:UploadLayerPart\",
         \"ecr:CompleteLayerUpload\",\"ecr:DescribeImages\"],
       \"Resource\":\"arn:aws:ecr:eu-west-2:${ACCOUNT_ID}:repository/lab-product-service\"},
      {\"Sid\":\"ECSDescribe\",\"Effect\":\"Allow\",
       \"Action\":[\"ecs:DescribeTaskDefinition\",\"ecs:DescribeServices\"],
       \"Resource\":\"*\"},
      {\"Sid\":\"ECSDeploy\",\"Effect\":\"Allow\",
       \"Action\":[\"ecs:RegisterTaskDefinition\",\"ecs:UpdateService\"],
       \"Resource\":[
         \"arn:aws:ecs:eu-west-2:${ACCOUNT_ID}:cluster/lab-cluster\",
         \"arn:aws:ecs:eu-west-2:${ACCOUNT_ID}:service/lab-cluster/lab-product-service\",
         \"arn:aws:ecs:eu-west-2:${ACCOUNT_ID}:task-definition/lab-product-service:*\"]},
      {\"Sid\":\"IAMPassRole\",\"Effect\":\"Allow\",\"Action\":\"iam:PassRole\",
       \"Resource\":[
         \"arn:aws:iam::${ACCOUNT_ID}:role/lab-ecs-execution-role\",
         \"arn:aws:iam::${ACCOUNT_ID}:role/lab-ecs-task-role\"]}
    ]
  }"
```

### Note the role ARN

```bash
aws iam get-role \
  --role-name lab-github-deploy-role \
  --query 'Role.Arn' --output text
# e.g. arn:aws:iam::123456789012:role/lab-github-deploy-role
```

---

## Part C — GitHub: Add Repository Secrets

GitHub Actions reads these secrets during the workflow run. No AWS credentials are stored — only the role ARN and ALB URL.

**GitHub web UI:**
1. Open your repository on GitHub.
2. **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
3. Add each secret:

| Secret name | Value |
|---|---|
| `AWS_DEPLOY_ROLE_ARN` | The role ARN from Part B (e.g. `arn:aws:iam::123456789012:role/lab-github-deploy-role`) |
| `ALB_URL` | Your ALB DNS name with `http://` prefix (e.g. `http://lab-alb-xxx.eu-west-2.elb.amazonaws.com`) |

**GitHub CLI alternative:**
```bash
# From inside the repo directory
gh secret set AWS_DEPLOY_ROLE_ARN
# Paste the role ARN when prompted

gh secret set ALB_URL
# Paste the ALB URL when prompted

gh secret list  # verify both appear
```

---

## Part D — Copy the application code

This repo uses the same product-service code as Lab 2. Copy the `services/product-service/` directory from Lab 2, or re-use the code already in this repo.

The workflow file at `.github/workflows/product-service.yml` is already present.

---

## Part E — Trigger the pipeline

### First successful run

```bash
# Make a small visible change to trigger the workflow
echo "# CI/CD lab" >> services/product-service/README.md
git add services/product-service/README.md
git commit -m "trigger: first pipeline run"
git push origin main
```

**GitHub web UI — watch the run:**
1. Go to your repository → **Actions** tab.
2. Click the workflow run for your commit.
3. You see two jobs: **CI — build and test** and **CD — push and deploy**.
4. Click each job to expand the steps and see real-time output.
5. The CD job's **Deploy to ECS** step shows the deployment progress and waits for stability.

**AWS Console — verify the deployment:**
- **ECS → Clusters → lab-cluster → Services → lab-product-service → Deployments** tab: the new deployment shows PRIMARY status with tasks running.
- **ECR → Repositories → lab-product-service → Images**: a new image tagged with the git SHA appears alongside `latest`.

### Demonstrate CI catching a broken test

```bash
# Break a test in ProductControllerTest.java — change expected value to something wrong
# e.g. .andExpect(jsonPath("$[0].name").value("WRONG"))
git add services/product-service/src/test/
git commit -m "demo: broken test"
git push origin main
```

**GitHub Actions:** The CI job fails at **Unit tests**. The CD job shows **Skipped** — it never runs.

**Fix and restore:**
```bash
git revert HEAD
git push origin main
```

---

## Part F — Tear Down

### Remove GitHub configuration

**GitHub web UI:** Settings → Secrets and variables → Actions → delete `AWS_DEPLOY_ROLE_ARN` and `ALB_URL`.

```bash
gh secret delete AWS_DEPLOY_ROLE_ARN
gh secret delete ALB_URL
```

### Remove AWS IAM resources

```bash
aws iam delete-role-policy \
  --role-name lab-github-deploy-role \
  --policy-name lab-github-deploy-policy

aws iam delete-role --role-name lab-github-deploy-role
```

**Console:** IAM → Roles → `lab-github-deploy-role` → **Delete**.

### Remove OIDC provider (optional — only if no other repos use it)

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws iam delete-open-id-connect-provider \
  --open-id-connect-provider-arn \
  arn:aws:iam::${ACCOUNT_ID}:oidc-provider/token.actions.githubusercontent.com
```

**Console:** IAM → Identity providers → `token.actions.githubusercontent.com` → **Delete**.

### Tear down ECS and VPC

Follow the tear-down sections in the Lab 2 and Lab 1 setup guides.

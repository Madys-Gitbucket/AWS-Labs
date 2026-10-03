# Setup Guide — Three-Tier Web App on AWS

Complete walkthrough: build the VPC, subnets, security groups, RDS database, EC2 instances, and Application Load Balancer entirely through the AWS Console. CLI alternatives are provided for every step.

---

## What you will build

```
Internet
   │  HTTP :80
   ▼
Application Load Balancer  (public subnets — 2 AZs)
   │  port 8080
   ▼
EC2 × 2  (private app subnets — one per AZ)
Spring Boot app, Java 21, reads/writes MySQL
   │  JDBC :3306
   ▼
RDS MySQL 8.0  (private data subnets — 2 AZs)
```

---

## Prerequisites

| Tool | Version | Install / check |
|------|---------|-----------------|
| AWS Account | — | https://aws.amazon.com |
| AWS CLI | v2 | `aws --version` |
| Java | 21 | `java --version` |
| Maven | 3.9+ | `mvn --version` |
| Git | any | `git --version` |

### Configure AWS CLI

```bash
aws configure
# Enter: Access Key ID, Secret Access Key, region (eu-west-2), output (json)
aws sts get-caller-identity   # verify
```

---

## Cost estimate

| Resource | Rate | ~4 hr lab |
|---|---|---|
| EC2 t3.small × 2 | $0.023/hr each | ~$0.18 |
| ALB | $0.022/hr + LCU | ~$0.09 |
| RDS db.t3.micro (Single-AZ) | $0.017/hr | ~$0.07 |
| NAT Gateway | $0.045/hr + data | ~$0.18 |
| **Total** | | **~$0.55** |

> **Destroy everything immediately when done** — RDS and NAT Gateway costs accumulate whether or not traffic is flowing.

---

## Part 1 — VPC and Networking

### Step 1.1 — Create the VPC

**Console:**
1. Go to **VPC** → **Your VPCs** → **Create VPC**.
2. Select **VPC only** (not VPC and more — we'll create subnets manually so each step is visible).
3. Name tag: `lab-vpc`
4. IPv4 CIDR: `10.0.0.0/16`
5. Leave IPv6 and tenancy as defaults.
6. Click **Create VPC**.

**CLI:**
```bash
VPC_ID=$(aws ec2 create-vpc \
  --cidr-block 10.0.0.0/16 \
  --query 'Vpc.VpcId' --output text --region eu-west-2)

aws ec2 create-tags \
  --resources "$VPC_ID" \
  --tags Key=Name,Value=lab-vpc \
  --region eu-west-2

echo "VPC: $VPC_ID"
```

Also enable DNS hostnames (required for RDS):

**Console:** Select `lab-vpc` → **Actions** → **Edit VPC settings** → tick **Enable DNS hostnames** → **Save**.

```bash
aws ec2 modify-vpc-attribute \
  --vpc-id "$VPC_ID" --enable-dns-hostnames \
  --region eu-west-2
```

---

### Step 1.2 — Create subnets (6 total)

**Console:** Go to **VPC** → **Subnets** → **Create subnet** → select `lab-vpc`.
Create one subnet at a time. Repeat for all six rows in this table:

| Name | AZ | CIDR | Type |
|------|----|------|------|
| lab-public-a | eu-west-2a | 10.0.1.0/24 | Public (ALB) |
| lab-public-b | eu-west-2b | 10.0.2.0/24 | Public (ALB) |
| lab-private-app-a | eu-west-2a | 10.0.3.0/24 | Private (EC2) |
| lab-private-app-b | eu-west-2b | 10.0.4.0/24 | Private (EC2) |
| lab-private-data-a | eu-west-2a | 10.0.5.0/24 | Private (RDS) |
| lab-private-data-b | eu-west-2b | 10.0.6.0/24 | Private (RDS) |

For each subnet: fill **Subnet name**, choose the **Availability Zone**, set **IPv4 CIDR**, click **Create subnet**.

Enable auto-assign public IP on the two public subnets:

**Console:** Select `lab-public-a` → **Actions** → **Edit subnet settings** → tick **Enable auto-assign public IPv4 address** → **Save**. Repeat for `lab-public-b`.

**CLI:**
```bash
# Save AZ lookups
AZ_A="eu-west-2a"
AZ_B="eu-west-2b"

PUB_A=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.1.0/24 \
  --availability-zone $AZ_A --query 'Subnet.SubnetId' --output text --region eu-west-2)
PUB_B=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.2.0/24 \
  --availability-zone $AZ_B --query 'Subnet.SubnetId' --output text --region eu-west-2)
APP_A=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.3.0/24 \
  --availability-zone $AZ_A --query 'Subnet.SubnetId' --output text --region eu-west-2)
APP_B=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.4.0/24 \
  --availability-zone $AZ_B --query 'Subnet.SubnetId' --output text --region eu-west-2)
DATA_A=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.5.0/24 \
  --availability-zone $AZ_A --query 'Subnet.SubnetId' --output text --region eu-west-2)
DATA_B=$(aws ec2 create-subnet --vpc-id $VPC_ID --cidr-block 10.0.6.0/24 \
  --availability-zone $AZ_B --query 'Subnet.SubnetId' --output text --region eu-west-2)

# Tag all subnets
for id_name in "$PUB_A:lab-public-a" "$PUB_B:lab-public-b" \
               "$APP_A:lab-private-app-a" "$APP_B:lab-private-app-b" \
               "$DATA_A:lab-private-data-a" "$DATA_B:lab-private-data-b"; do
  id="${id_name%%:*}"; name="${id_name##*:}"
  aws ec2 create-tags --resources "$id" --tags Key=Name,Value="$name" --region eu-west-2
done

# Auto-assign public IP on public subnets
aws ec2 modify-subnet-attribute --subnet-id $PUB_A \
  --map-public-ip-on-launch --region eu-west-2
aws ec2 modify-subnet-attribute --subnet-id $PUB_B \
  --map-public-ip-on-launch --region eu-west-2
```

---

### Step 1.3 — Create Internet Gateway

**Console:** **VPC** → **Internet Gateways** → **Create internet gateway** → Name: `lab-igw` → **Create**.
Then: select `lab-igw` → **Actions** → **Attach to VPC** → select `lab-vpc` → **Attach**.

**CLI:**
```bash
IGW_ID=$(aws ec2 create-internet-gateway \
  --query 'InternetGateway.InternetGatewayId' --output text --region eu-west-2)
aws ec2 create-tags --resources $IGW_ID --tags Key=Name,Value=lab-igw --region eu-west-2
aws ec2 attach-internet-gateway \
  --internet-gateway-id $IGW_ID --vpc-id $VPC_ID --region eu-west-2
```

---

### Step 1.4 — Create NAT Gateway

NAT Gateway allows EC2 instances in private subnets to reach the internet (for SSM, yum, CloudWatch).

**Console:**
1. **VPC** → **NAT Gateways** → **Create NAT gateway**.
2. Name: `lab-nat-gw`
3. Subnet: **lab-public-a** (NAT must live in a public subnet).
4. Connectivity type: **Public**.
5. Click **Allocate Elastic IP** to create a new EIP automatically.
6. Click **Create NAT gateway**.

Wait ~2 minutes for status to become **Available**.

**CLI:**
```bash
EIP_ALLOC=$(aws ec2 allocate-address \
  --domain vpc --query 'AllocationId' --output text --region eu-west-2)

NAT_ID=$(aws ec2 create-nat-gateway \
  --subnet-id $PUB_A \
  --allocation-id $EIP_ALLOC \
  --query 'NatGateway.NatGatewayId' --output text --region eu-west-2)
aws ec2 create-tags --resources $NAT_ID --tags Key=Name,Value=lab-nat-gw --region eu-west-2

# Wait for available state
aws ec2 wait nat-gateway-available --nat-gateway-ids $NAT_ID --region eu-west-2
echo "NAT Gateway ready: $NAT_ID"
```

---

### Step 1.5 — Create route tables

You need two route tables: one public (routes to IGW) and one private (routes to NAT).

#### Public route table

**Console:**
1. **VPC** → **Route Tables** → **Create route table** → Name: `lab-public-rt`, VPC: `lab-vpc` → **Create**.
2. Select `lab-public-rt` → **Routes** tab → **Edit routes** → **Add route**:
   - Destination: `0.0.0.0/0` → Target: `lab-igw` → **Save**.
3. **Subnet associations** tab → **Edit subnet associations** → tick **lab-public-a** and **lab-public-b** → **Save**.

#### Private route table

**Console:**
1. Create another: Name: `lab-private-rt`, VPC: `lab-vpc`.
2. **Routes** → **Edit routes** → Add route: `0.0.0.0/0` → Target: **NAT Gateway** → `lab-nat-gw` → **Save**.
3. **Subnet associations** → tick all four private subnets (app-a, app-b, data-a, data-b) → **Save**.

**CLI:**
```bash
# Public route table
PUB_RT=$(aws ec2 create-route-table --vpc-id $VPC_ID \
  --query 'RouteTable.RouteTableId' --output text --region eu-west-2)
aws ec2 create-tags --resources $PUB_RT --tags Key=Name,Value=lab-public-rt --region eu-west-2
aws ec2 create-route --route-table-id $PUB_RT \
  --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW_ID --region eu-west-2
aws ec2 associate-route-table --route-table-id $PUB_RT --subnet-id $PUB_A --region eu-west-2
aws ec2 associate-route-table --route-table-id $PUB_RT --subnet-id $PUB_B --region eu-west-2

# Private route table
PRIV_RT=$(aws ec2 create-route-table --vpc-id $VPC_ID \
  --query 'RouteTable.RouteTableId' --output text --region eu-west-2)
aws ec2 create-tags --resources $PRIV_RT --tags Key=Name,Value=lab-private-rt --region eu-west-2
aws ec2 create-route --route-table-id $PRIV_RT \
  --destination-cidr-block 0.0.0.0/0 --nat-gateway-id $NAT_ID --region eu-west-2
for subnet in $APP_A $APP_B $DATA_A $DATA_B; do
  aws ec2 associate-route-table \
    --route-table-id $PRIV_RT --subnet-id $subnet --region eu-west-2
done
```

---

## Part 2 — Security Groups

### Step 2.1 — ALB Security Group

**Console:** **VPC** → **Security Groups** → **Create security group**:
- Name: `lab-alb-sg`
- Description: `ALB — inbound HTTP from internet`
- VPC: `lab-vpc`
- **Inbound rules**: Add rule → Type: **HTTP**, Source: **Anywhere-IPv4** (`0.0.0.0/0`)
- **Outbound rules**: leave default (all traffic)
- **Create security group**.

**CLI:**
```bash
ALB_SG=$(aws ec2 create-security-group \
  --group-name lab-alb-sg \
  --description "ALB inbound HTTP" \
  --vpc-id $VPC_ID \
  --query 'GroupId' --output text --region eu-west-2)
aws ec2 authorize-security-group-ingress \
  --group-id $ALB_SG \
  --protocol tcp --port 80 --cidr 0.0.0.0/0 \
  --region eu-west-2
aws ec2 create-tags --resources $ALB_SG --tags Key=Name,Value=lab-alb-sg --region eu-west-2
```

### Step 2.2 — App Tier Security Group

**Console:** Create security group:
- Name: `lab-app-sg`
- VPC: `lab-vpc`
- **Inbound rules**: Add rule → Type: **Custom TCP**, Port: `8080`, Source: **Custom** → type `lab-alb-sg` in the search box and select it (this creates an SG-reference rule, not a CIDR)
- **Create security group**.

**CLI:**
```bash
APP_SG=$(aws ec2 create-security-group \
  --group-name lab-app-sg \
  --description "App tier — port 8080 from ALB only" \
  --vpc-id $VPC_ID \
  --query 'GroupId' --output text --region eu-west-2)
aws ec2 authorize-security-group-ingress \
  --group-id $APP_SG \
  --protocol tcp --port 8080 \
  --source-group $ALB_SG \
  --region eu-west-2
aws ec2 create-tags --resources $APP_SG --tags Key=Name,Value=lab-app-sg --region eu-west-2
```

### Step 2.3 — DB Security Group

**Console:** Create security group:
- Name: `lab-db-sg`
- VPC: `lab-vpc`
- **Inbound rules**: Add rule → Type: **MySQL/Aurora** (port 3306), Source: **Custom** → select `lab-app-sg`
- **Create security group**.

**CLI:**
```bash
DB_SG=$(aws ec2 create-security-group \
  --group-name lab-db-sg \
  --description "DB tier — MySQL from app tier only" \
  --vpc-id $VPC_ID \
  --query 'GroupId' --output text --region eu-west-2)
aws ec2 authorize-security-group-ingress \
  --group-id $DB_SG \
  --protocol tcp --port 3306 \
  --source-group $APP_SG \
  --region eu-west-2
aws ec2 create-tags --resources $DB_SG --tags Key=Name,Value=lab-db-sg --region eu-west-2
```

---

## Part 3 — IAM Role for EC2

EC2 instances need permissions to: communicate via SSM Session Manager, write logs to CloudWatch, and download the JAR from S3.

### Step 3.1 — Create the IAM role

**Console:**
1. **IAM** → **Roles** → **Create role**.
2. Trusted entity type: **AWS service** → Use case: **EC2** → **Next**.
3. Search and attach these managed policies (tick each):
   - `AmazonSSMManagedInstanceCore`
   - `CloudWatchLogsFullAccess`
   - `AmazonS3ReadOnlyAccess`
4. Role name: `lab-ec2-role` → **Create role**.

**CLI:**
```bash
aws iam create-role \
  --role-name lab-ec2-role \
  --assume-role-policy-document '{
    "Version":"2012-10-17",
    "Statement":[{"Effect":"Allow","Principal":{"Service":"ec2.amazonaws.com"},
    "Action":"sts:AssumeRole"}]}' \
  --region eu-west-2

for policy in AmazonSSMManagedInstanceCore CloudWatchLogsFullAccess AmazonS3ReadOnlyAccess; do
  aws iam attach-role-policy \
    --role-name lab-ec2-role \
    --policy-arn "arn:aws:iam::aws:policy/$policy"
done

aws iam create-instance-profile \
  --instance-profile-name lab-ec2-profile
aws iam add-role-to-instance-profile \
  --instance-profile-name lab-ec2-profile \
  --role-name lab-ec2-role
```

---

## Part 4 — RDS MySQL

### Step 4.1 — Create a DB Subnet Group

The DB subnet group tells RDS which subnets it may use. It must include both data subnets (different AZs).

**Console:**
1. **RDS** → **Subnet groups** → **Create DB subnet group**.
2. Name: `lab-db-subnet-group`
3. Description: `Lab data tier subnets`
4. VPC: `lab-vpc`
5. Under **Add subnets**: select `eu-west-2a` and add `lab-private-data-a`; select `eu-west-2b` and add `lab-private-data-b`.
6. **Create**.

**CLI:**
```bash
aws rds create-db-subnet-group \
  --db-subnet-group-name lab-db-subnet-group \
  --db-subnet-group-description "Lab data tier subnets" \
  --subnet-ids $DATA_A $DATA_B \
  --region eu-west-2
```

### Step 4.2 — Create the RDS instance

**Console:**
1. **RDS** → **Databases** → **Create database**.
2. **Standard create**.
3. Engine: **MySQL** → Version: **MySQL 8.0.x** (latest 8.0).
4. Templates: **Free tier** (uses Single-AZ, db.t3.micro — fine for the lab).
5. DB instance identifier: `lab-mysql`
6. Master username: `admin`
7. Master password: choose a strong password and note it down.
8. DB instance class: **db.t3.micro** (pre-selected by Free tier template).
9. Storage: 20 GiB gp2, disable auto-scaling.
10. Connectivity:
    - VPC: `lab-vpc`
    - DB subnet group: `lab-db-subnet-group`
    - Public access: **No**
    - VPC security group: remove `default`, add `lab-db-sg`
    - Availability zone: `eu-west-2a`
11. Initial database name: `webapp`
12. Disable automated backups for the lab (set retention to 0 days) to speed up creation.
13. **Create database**.

> Creation takes ~5 minutes. Continue to Part 5 while waiting.

**CLI:**
```bash
aws rds create-db-instance \
  --db-instance-identifier lab-mysql \
  --db-instance-class db.t3.micro \
  --engine mysql \
  --engine-version "8.0" \
  --master-username admin \
  --master-user-password "<YOUR_RDS_MASTER_PASSWORD>" \
  --db-name webapp \
  --vpc-security-group-ids $DB_SG \
  --db-subnet-group-name lab-db-subnet-group \
  --no-multi-az \
  --allocated-storage 20 \
  --storage-type gp2 \
  --backup-retention-period 0 \
  --no-publicly-accessible \
  --region eu-west-2

# Wait for available (takes ~5 mins)
aws rds wait db-instance-available \
  --db-instance-identifier lab-mysql --region eu-west-2

# Get the endpoint
RDS_ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier lab-mysql \
  --query 'DBInstances[0].Endpoint.Address' \
  --output text --region eu-west-2)
echo "RDS endpoint: $RDS_ENDPOINT"
```

### Step 4.3 — Create the application database user

Connect to MySQL using the admin credentials and create a less-privileged app user.

You cannot reach RDS from your laptop (private subnet). Use an EC2 instance after it is created in Part 5 to run the following, or use the SSM Session Manager shell.

```sql
-- Run after EC2 is up, via SSM session on one EC2 instance
-- Install MySQL client on the instance first:
--   sudo yum install -y mysql

mysql -h <rds-endpoint> -u admin -p

-- Inside MySQL:
CREATE USER 'webapp'@'%' IDENTIFIED BY '<YOUR_DB_PASSWORD>';
GRANT SELECT, INSERT, UPDATE, DELETE ON webapp.* TO 'webapp'@'%';
FLUSH PRIVILEGES;
EXIT;
```

---

## Part 5 — EC2 Instances (App Tier)

Look up the latest Amazon Linux 2023 AMI for your region before creating instances.

**CLI:**
```bash
AMI_ID=$(aws ec2 describe-images \
  --owners amazon \
  --filters "Name=name,Values=al2023-ami-2023*-x86_64" \
            "Name=state,Values=available" \
  --query "sort_by(Images,&CreationDate)[-1].ImageId" \
  --output text --region eu-west-2)
echo "AMI: $AMI_ID"
```

**Console:** **EC2** → **AMI Catalog** → **AWS** tab → search `Amazon Linux 2023` → note the AMI ID shown.

### Step 5.1 — Launch EC2 in AZ-a

**Console:**
1. **EC2** → **Instances** → **Launch instances**.
2. Name: `lab-app-a`
3. AMI: search for `Amazon Linux 2023 AMI` → select it.
4. Instance type: `t3.small`.
5. Key pair: **Proceed without a key pair** (we use SSM).
6. Network settings → **Edit**:
   - VPC: `lab-vpc`
   - Subnet: `lab-private-app-a`
   - Auto-assign public IP: **Disable**
   - Security group: select existing `lab-app-sg` (remove the default security group)
7. Advanced details:
   - IAM instance profile: `lab-ec2-profile`
   - User data: paste the full contents of `scripts/userdata.sh`
8. **Launch instance**.

### Step 5.2 — Launch EC2 in AZ-b

Repeat Step 5.1 with:
- Name: `lab-app-b`
- Subnet: `lab-private-app-b`
- Everything else identical.

**CLI (both instances):**
```bash
USER_DATA=$(base64 -w0 scripts/userdata.sh)

for az_subnet_name in "${APP_A}:lab-app-a" "${APP_B}:lab-app-b"; do
  subnet="${az_subnet_name%%:*}"
  name="${az_subnet_name##*:}"
  aws ec2 run-instances \
    --image-id "$AMI_ID" \
    --instance-type t3.small \
    --subnet-id "$subnet" \
    --security-group-ids $APP_SG \
    --iam-instance-profile Name=lab-ec2-profile \
    --no-associate-public-ip-address \
    --user-data "$USER_DATA" \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name}]" \
    --region eu-west-2 \
    --query 'Instances[0].InstanceId' --output text
done
```

**Wait for EC2 status checks:**

**Console:** **EC2** → **Instances** → select both instances → **Status checks** tab → wait for both **System reachability** and **Instance reachability** to show green.

```bash
# Wait for each instance (replace i-xxx with actual IDs)
aws ec2 wait instance-status-ok \
  --instance-ids <instance-id-a> <instance-id-b> \
  --region eu-west-2
```

---

## Part 6 — Application Load Balancer

### Step 6.1 — Create a Target Group

**Console:**
1. **EC2** → **Target groups** → **Create target group**.
2. Target type: **Instances**.
3. Name: `lab-app-tg`
4. Protocol: **HTTP**, Port: **8080**
5. VPC: `lab-vpc`
6. Health check path: `/health`
7. Healthy threshold: **2**, Unhealthy threshold: **3**, Interval: **30 seconds**
8. **Next** → select both EC2 instances (`lab-app-a`, `lab-app-b`) → **Include as pending below** → **Create target group**.

**CLI:**
```bash
TG_ARN=$(aws elbv2 create-target-group \
  --name lab-app-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id $VPC_ID \
  --health-check-path /health \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --health-check-interval-seconds 30 \
  --target-type instance \
  --query 'TargetGroups[0].TargetGroupArn' --output text \
  --region eu-west-2)

# Register both instances (replace with actual IDs)
aws elbv2 register-targets \
  --target-group-arn $TG_ARN \
  --targets Id=<instance-id-a> Id=<instance-id-b> \
  --region eu-west-2
```

### Step 6.2 — Create the ALB

**Console:**
1. **EC2** → **Load Balancers** → **Create load balancer** → **Application Load Balancer** → **Create**.
2. Name: `lab-alb`
3. Scheme: **Internet-facing**
4. IP address type: **IPv4**
5. VPC: `lab-vpc`
6. Mappings: tick **eu-west-2a** (select `lab-public-a`) and **eu-west-2b** (select `lab-public-b`).
7. Security groups: remove `default`, select `lab-alb-sg`.
8. Listeners: HTTP:80 → **Default action: Forward to** `lab-app-tg`.
9. **Create load balancer**.

**CLI:**
```bash
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name lab-alb \
  --subnets $PUB_A $PUB_B \
  --security-groups $ALB_SG \
  --scheme internet-facing \
  --type application \
  --query 'LoadBalancers[0].LoadBalancerArn' --output text \
  --region eu-west-2)

# Wait for active
aws elbv2 wait load-balancer-available \
  --load-balancer-arns $ALB_ARN --region eu-west-2

ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns $ALB_ARN \
  --query 'LoadBalancers[0].DNSName' --output text --region eu-west-2)
echo "ALB DNS: $ALB_DNS"

# Add HTTP listener
aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTP --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TG_ARN \
  --region eu-west-2
```

---

## Part 7 — Build and Deploy the Application

### Step 7.1 — Build the JAR locally

```bash
cd application
mvn test
mvn package -DskipTests
ls target/webapp.jar
cd ..
```

### Step 7.2 — Create an S3 bucket for deployment artefacts

**Console:** **S3** → **Create bucket** → name: `lab-deploy-<your-account-id>` → region: `eu-west-2` → all other defaults → **Create bucket**.

```bash
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
aws s3 mb s3://lab-deploy-${ACCOUNT_ID} --region eu-west-2
```

### Step 7.3 — Upload the JAR

**Console:** Open the bucket → **Upload** → **Add files** → select `application/target/webapp.jar` → **Upload**.

```bash
aws s3 cp application/target/webapp.jar \
  s3://lab-deploy-${ACCOUNT_ID}/webapp.jar --region eu-west-2
```

### Step 7.4 — Deploy to EC2 via SSM Run Command

You need to run this command on **both** EC2 instances. Replace `<rds-endpoint>` and `<db-password>` with your values.

**Console — SSM Run Command:**
1. **Systems Manager** → **Run Command** → **Run command**.
2. Search for `AWS-RunShellScript` → select it.
3. **Target selection**: **Choose instances manually** → tick **lab-app-a** and **lab-app-b**.
4. **Commands** (paste this block, fill in the placeholders):
   ```
   aws s3 cp s3://lab-deploy-<account-id>/webapp.jar /opt/webapp/webapp.jar --region eu-west-2
   chown webapp:webapp /opt/webapp/webapp.jar
   sed -i 's|REPLACE_WITH_RDS_ENDPOINT|<rds-endpoint>|g' /opt/webapp/env.conf
   sed -i 's|REPLACE_WITH_DB_PASSWORD|<YOUR_DB_PASSWORD>|g' /opt/webapp/env.conf
   systemctl restart webapp
   systemctl status webapp --no-pager
   ```
5. **Run** → wait for **Success** status → click each instance ID to view output.

**CLI:**
```bash
RDS_ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier lab-mysql \
  --query 'DBInstances[0].Endpoint.Address' \
  --output text --region eu-west-2)

for INSTANCE_ID in <instance-id-a> <instance-id-b>; do
  aws ssm send-command \
    --region eu-west-2 \
    --instance-ids "$INSTANCE_ID" \
    --document-name "AWS-RunShellScript" \
    --parameters "commands=[
      \"aws s3 cp s3://lab-deploy-${ACCOUNT_ID}/webapp.jar /opt/webapp/webapp.jar --region eu-west-2\",
      \"chown webapp:webapp /opt/webapp/webapp.jar\",
      \"sed -i 's|REPLACE_WITH_RDS_ENDPOINT|${RDS_ENDPOINT}|g' /opt/webapp/env.conf\",
      \"sed -i 's|REPLACE_WITH_DB_PASSWORD|<YOUR_DB_PASSWORD>|g' /opt/webapp/env.conf\",
      \"systemctl restart webapp\",
      \"systemctl status webapp --no-pager\"
    ]" \
    --output text --query 'Command.CommandId'
done
```

### Step 7.5 — Create the database schema and seed data

Connect to one EC2 instance via SSM to initialise the database.

**Console:** **EC2** → **Instances** → select `lab-app-a` → **Connect** → **Session Manager** tab → **Connect**.

```bash
# Inside the SSM session on lab-app-a:
sudo yum install -y mysql
mysql -h <rds-endpoint> -u admin -p

-- In MySQL:
CREATE USER 'webapp'@'%' IDENTIFIED BY '<YOUR_DB_PASSWORD>';
GRANT SELECT, INSERT, UPDATE, DELETE ON webapp.* TO 'webapp'@'%';
FLUSH PRIVILEGES;
EXIT;
```

Then seed the sample data:
```bash
mysql -h <rds-endpoint> -u webapp -p webapp < /opt/webapp/data-init.sql
# (Upload data-init.sql via S3 or copy-paste its contents)
```

---

## Part 8 — Verify the Deployment

### Check ALB target health

**Console:** **EC2** → **Target groups** → `lab-app-tg` → **Targets** tab. Wait until both instances show **healthy** (allow up to 90 seconds after app start).

**CLI:**
```bash
aws elbv2 describe-target-health \
  --target-group-arn $TG_ARN \
  --query 'TargetHealthDescriptions[*].[Target.Id,TargetHealth.State]' \
  --output table --region eu-west-2
```

### Test the API

```bash
ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns $ALB_ARN \
  --query 'LoadBalancers[0].DNSName' --output text --region eu-west-2)

# Health check
curl http://${ALB_DNS}/health
# {"status":"UP","service":"webapp","version":"1.0.0"}

# List all products (requires DB seeded)
curl http://${ALB_DNS}/products

# Get one product
curl http://${ALB_DNS}/products/1

# Create a product
curl -X POST http://${ALB_DNS}/products \
  -H "Content-Type: application/json" \
  -d '{"name":"New Item","category":"Hardware","price":19.99,"stock":200}'

# Filter by category
curl http://${ALB_DNS}/products?category=Services
```

You can also paste `http://<alb-dns>/health` and `http://<alb-dns>/products` into a browser.

### Check CloudWatch Logs

**Console:** **CloudWatch** → **Log groups** → `/ec2/webapp` → click a log stream to view Spring Boot output.

**CLI:**
```bash
aws logs tail /ec2/webapp --follow --region eu-west-2
```

### Full verification checklist

| Where | What to confirm |
|-------|----------------|
| **VPC → Your VPCs** | `lab-vpc` (10.0.0.0/16) present |
| **VPC → Subnets** | 6 subnets, correct CIDRs and AZs |
| **VPC → Route Tables** | `lab-public-rt` has route to IGW; `lab-private-rt` has route to NAT |
| **VPC → Internet Gateways** | `lab-igw` attached to `lab-vpc` |
| **VPC → NAT Gateways** | `lab-nat-gw` Available, in `lab-public-a` |
| **VPC → Security Groups** | `lab-alb-sg`, `lab-app-sg`, `lab-db-sg` all present with correct rules |
| **EC2 → Instances** | `lab-app-a` and `lab-app-b` running, IAM profile attached, private IPs in correct subnets |
| **EC2 → Load Balancers** | `lab-alb` active, spans 2 AZs |
| **EC2 → Target Groups** | `lab-app-tg` shows both targets as **healthy** |
| **RDS → Databases** | `lab-mysql` **Available**, in `lab-vpc`, no public access |
| **IAM → Roles** | `lab-ec2-role` with SSM, CloudWatch, S3 policies |
| **CloudWatch → Log groups** | `/ec2/webapp` exists with streams |

---

## Part 9 — Tear Down (complete cleanup)

**Destroy in this order** to avoid dependency errors.

### 9.1 — Delete the Load Balancer and Target Group

**Console:**
1. **EC2** → **Load Balancers** → select `lab-alb` → **Actions** → **Delete load balancer** → confirm.
2. **EC2** → **Target groups** → select `lab-app-tg` → **Actions** → **Delete** → confirm.

**CLI:**
```bash
aws elbv2 delete-load-balancer --load-balancer-arn $ALB_ARN --region eu-west-2
aws elbv2 wait load-balancers-deleted --load-balancer-arns $ALB_ARN --region eu-west-2
aws elbv2 delete-target-group --target-group-arn $TG_ARN --region eu-west-2
```

### 9.2 — Terminate EC2 Instances

**Console:** **EC2** → **Instances** → select `lab-app-a` and `lab-app-b` → **Instance state** → **Terminate instance** → **Terminate**.

**CLI:**
```bash
aws ec2 terminate-instances \
  --instance-ids <instance-id-a> <instance-id-b> \
  --region eu-west-2
aws ec2 wait instance-terminated \
  --instance-ids <instance-id-a> <instance-id-b> \
  --region eu-west-2
```

### 9.3 — Delete RDS Instance

**Console:** **RDS** → **Databases** → select `lab-mysql` → **Actions** → **Delete** → untick **Create final snapshot** → tick the acknowledgement → type `delete me` → **Delete**.

**CLI:**
```bash
aws rds delete-db-instance \
  --db-instance-identifier lab-mysql \
  --skip-final-snapshot \
  --delete-automated-backups \
  --region eu-west-2
aws rds wait db-instance-deleted \
  --db-instance-identifier lab-mysql --region eu-west-2
```

### 9.4 — Delete the NAT Gateway and release the Elastic IP

**Console:** **VPC** → **NAT Gateways** → select `lab-nat-gw` → **Actions** → **Delete NAT gateway** → confirm. Wait for status **Deleted**.
Then: **VPC** → **Elastic IPs** → select the EIP with no association → **Actions** → **Release Elastic IP address**.

**CLI:**
```bash
aws ec2 delete-nat-gateway --nat-gateway-id $NAT_ID --region eu-west-2
aws ec2 wait nat-gateway-deleted --nat-gateway-ids $NAT_ID --region eu-west-2 2>/dev/null || true
aws ec2 release-address --allocation-id $EIP_ALLOC --region eu-west-2
```

### 9.5 — Delete the VPC and all remaining networking

**Console:**
1. **VPC** → **Your VPCs** → select `lab-vpc` → **Actions** → **Delete VPC**.
2. The console shows a list of dependent resources that will also be deleted (subnets, route tables, security groups, IGW associations). Review the list and click **Delete**.

**CLI:**
```bash
# Delete subnets, route tables, security groups, IGW (must remove associations first)
aws ec2 detach-internet-gateway \
  --internet-gateway-id $IGW_ID --vpc-id $VPC_ID --region eu-west-2
aws ec2 delete-internet-gateway --internet-gateway-id $IGW_ID --region eu-west-2

for subnet in $PUB_A $PUB_B $APP_A $APP_B $DATA_A $DATA_B; do
  aws ec2 delete-subnet --subnet-id $subnet --region eu-west-2
done

aws ec2 delete-route-table --route-table-id $PUB_RT --region eu-west-2
aws ec2 delete-route-table --route-table-id $PRIV_RT --region eu-west-2

for sg in $ALB_SG $APP_SG $DB_SG; do
  aws ec2 delete-security-group --group-id $sg --region eu-west-2
done

aws ec2 delete-vpc --vpc-id $VPC_ID --region eu-west-2
```

### 9.6 — Delete remaining resources

**Console / CLI:**
```bash
# Delete DB subnet group
aws rds delete-db-subnet-group \
  --db-subnet-group-name lab-db-subnet-group --region eu-west-2

# Delete IAM resources
aws iam remove-role-from-instance-profile \
  --instance-profile-name lab-ec2-profile --role-name lab-ec2-role
aws iam delete-instance-profile --instance-profile-name lab-ec2-profile
for policy in AmazonSSMManagedInstanceCore CloudWatchLogsFullAccess AmazonS3ReadOnlyAccess; do
  aws iam detach-role-policy \
    --role-name lab-ec2-role \
    --policy-arn "arn:aws:iam::aws:policy/$policy"
done
aws iam delete-role --role-name lab-ec2-role

# Delete S3 bucket
aws s3 rb s3://lab-deploy-${ACCOUNT_ID} --force --region eu-west-2

# Delete CloudWatch log group
aws logs delete-log-group --log-group-name /ec2/webapp --region eu-west-2
```

**Console — CloudWatch:** **CloudWatch** → **Log groups** → tick `/ec2/webapp` → **Actions** → **Delete log group(s)**.

### 9.7 — Verify the bill

**AWS Console → Billing and Cost Management → Bills** → check that no EC2, RDS, NAT Gateway, or ALB line items remain. These are the most expensive resources if not fully deleted.

---

## Troubleshooting

| Symptom | Console check | CLI check |
|---------|--------------|-----------|
| ALB targets show `unhealthy` | EC2 → Target groups → lab-app-tg → Targets | `aws elbv2 describe-target-health --target-group-arn $TG_ARN` |
| Targets show `initial` for >3 min | App not started — check SSM Run Command status in Systems Manager → Run Command → Command history | `aws ssm list-command-invocations --details` |
| 503 from ALB | All targets unhealthy | Same as above |
| App starts but DB connection fails | Check env.conf values via SSM session | `aws ssm start-session --target <id>` then `cat /opt/webapp/env.conf` |
| EC2 not reachable via SSM | IAM profile not attached, or SSM agent not running | `aws ssm describe-instance-information --region eu-west-2` |
| RDS connection refused | DB SG allows only app-sg on 3306; verify security group source is correct | `aws rds describe-db-instances --db-instance-identifier lab-mysql` |
| NAT Gateway not working | Route table association — verify private subnets have route to NAT | VPC → Route Tables → check associations on lab-private-rt |

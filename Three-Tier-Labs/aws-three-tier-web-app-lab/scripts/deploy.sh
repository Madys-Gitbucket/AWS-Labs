#!/usr/bin/env bash
# deploy.sh — Upload the JAR to S3, then run it on EC2 via SSM
# Usage: ./scripts/deploy.sh <instance-id> <s3-bucket> <rds-endpoint> <db-password>
set -euo pipefail

INSTANCE_ID="${1:?Usage: deploy.sh <instance-id> <s3-bucket> <rds-endpoint> <db-password>}"
S3_BUCKET="${2:?}"
RDS_ENDPOINT="${3:?}"
DB_PASSWORD="${4:?}"
REGION="${AWS_REGION:-eu-west-2}"
JAR="application/target/webapp.jar"

echo "==> Building JAR"
(cd application && mvn --batch-mode package -DskipTests)

echo "==> Uploading to s3://${S3_BUCKET}/webapp.jar"
aws s3 cp "${JAR}" "s3://${S3_BUCKET}/webapp.jar" --region "${REGION}"

echo "==> Deploying via SSM"
aws ssm send-command \
  --region "${REGION}" \
  --instance-ids "${INSTANCE_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "commands=[
    \"aws s3 cp s3://${S3_BUCKET}/webapp.jar /opt/webapp/webapp.jar --region ${REGION}\",
    \"chown webapp:webapp /opt/webapp/webapp.jar\",
    \"sed -i 's|REPLACE_WITH_RDS_ENDPOINT|${RDS_ENDPOINT}|g' /opt/webapp/env.conf\",
    \"sed -i 's|REPLACE_WITH_DB_PASSWORD|${DB_PASSWORD}|g' /opt/webapp/env.conf\",
    \"systemctl restart webapp\",
    \"systemctl status webapp --no-pager\"
  ]" \
  --output text --query "Command.CommandId"

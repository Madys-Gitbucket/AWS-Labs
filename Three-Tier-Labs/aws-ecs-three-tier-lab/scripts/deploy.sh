#!/usr/bin/env bash
# deploy.sh — build, push to ECR, force new ECS deployment
# Usage: AWS_REGION=eu-west-2 ENVIRONMENT=lab ./scripts/deploy.sh
set -euo pipefail

REGION="${AWS_REGION:-eu-west-2}"
ENVIRONMENT="${ENVIRONMENT:-lab}"
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_HOST="${ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"
REPO="${ECR_HOST}/${ENVIRONMENT}-product-service"
CLUSTER="${ENVIRONMENT}-cluster"
SERVICE="${ENVIRONMENT}-product-service"

echo "==> ECR login"
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$ECR_HOST"

echo "==> Build JAR"
(cd services/product-service && mvn --batch-mode package -DskipTests)

echo "==> Build image (linux/amd64)"
docker build --platform linux/amd64 \
  -t "${REPO}:latest" \
  services/product-service/

echo "==> Push to ECR"
docker push "${REPO}:latest"

echo "==> Force new ECS deployment"
aws ecs update-service \
  --cluster "$CLUSTER" --service "$SERVICE" \
  --force-new-deployment --region "$REGION" \
  --output text --query "service.serviceName"

echo "==> Waiting for stable"
aws ecs wait services-stable \
  --cluster "$CLUSTER" --services "$SERVICE" --region "$REGION"
echo "==> Deploy complete"

#!/bin/bash
# EC2 User Data script — bootstraps the app tier
# Run automatically on first launch. Can also be run manually for re-bootstrapping.
set -euo pipefail

# ── Java 21 ──────────────────────────────────────────────────────────────────
yum install -y java-21-amazon-corretto-headless

# ── Create app directory and system user ─────────────────────────────────────
useradd -r -s /bin/false webapp 2>/dev/null || true
mkdir -p /opt/webapp
chown webapp:webapp /opt/webapp

# ── systemd service unit ──────────────────────────────────────────────────────
cat > /etc/systemd/system/webapp.service << 'EOF'
[Unit]
Description=Three-Tier Web App
After=network.target

[Service]
User=webapp
WorkingDirectory=/opt/webapp
ExecStart=/usr/bin/java -jar /opt/webapp/webapp.jar
Restart=on-failure
RestartSec=5
StandardOutput=journal
StandardError=journal
EnvironmentFile=/opt/webapp/env.conf

[Install]
WantedBy=multi-user.target
EOF

# ── Environment file placeholder (filled by deploy script) ───────────────────
cat > /opt/webapp/env.conf << 'EOF'
DB_HOST=REPLACE_WITH_RDS_ENDPOINT
DB_PORT=3306
DB_NAME=webapp
DB_USER=webapp
DB_PASSWORD=REPLACE_WITH_DB_PASSWORD
EOF

chown webapp:webapp /opt/webapp/env.conf
chmod 600 /opt/webapp/env.conf

# ── CloudWatch Agent ─────────────────────────────────────────────────────────
yum install -y amazon-cloudwatch-agent

cat > /opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json << 'EOF'
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/log/webapp.log",
            "log_group_name": "/ec2/webapp",
            "log_stream_name": "{instance_id}"
          }
        ]
      }
    }
  }
}
EOF

/opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl \
  -a fetch-config -m ec2 \
  -c file:/opt/aws/amazon-cloudwatch-agent/etc/amazon-cloudwatch-agent.json -s

systemctl daemon-reload
systemctl enable webapp
echo "Bootstrap complete. Deploy webapp.jar to /opt/webapp/ then: systemctl start webapp"

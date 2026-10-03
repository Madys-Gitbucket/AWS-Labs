-- Run this on the RDS instance after first deployment to seed sample data
-- Connect via: mysql -h <rds-endpoint> -u webapp -p webapp

INSERT INTO products (name, category, price, stock) VALUES
  ('Cloud Consulting Package', 'Services', 1250.00, 100),
  ('AWS Training Voucher',     'Training', 850.00,  50),
  ('Support Contract 1yr',     'Services', 3600.00, 25),
  ('Architecture Review',      'Services', 2000.00, 10),
  ('Security Assessment',      'Services', 1800.00, 15);

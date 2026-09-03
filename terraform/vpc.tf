# ── VPC ─────────────────────────────────────────────────────────────────────

resource "aws_vpc" "sandbox" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "civiform-sandbox-vpc" }
}

# ── Subnets ──────────────────────────────────────────────────────────────────
# 2 public (ALB), 2 private (ECS + RDS)

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_subnet" "public" {
  count             = 2
  vpc_id            = aws_vpc.sandbox.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index)
  availability_zone = data.aws_availability_zones.available.names[count.index]

  map_public_ip_on_launch = true
  tags                    = { Name = "civiform-sandbox-public-${count.index}" }
}

resource "aws_subnet" "private" {
  count             = 2
  vpc_id            = aws_vpc.sandbox.id
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)
  availability_zone = data.aws_availability_zones.available.names[count.index]

  tags = { Name = "civiform-sandbox-private-${count.index}" }
}

# ── Internet Gateway ──────────────────────────────────────────────────────────

resource "aws_internet_gateway" "sandbox" {
  vpc_id = aws_vpc.sandbox.id
  tags   = { Name = "civiform-sandbox-igw" }
}

# ── NAT Gateway (ECS tasks in private subnets need outbound internet to pull images) ──

resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "civiform-sandbox-nat-eip" }
}

resource "aws_nat_gateway" "sandbox" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public[0].id
  tags          = { Name = "civiform-sandbox-nat" }
  depends_on    = [aws_internet_gateway.sandbox]
}

# ── Route Tables ──────────────────────────────────────────────────────────────

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.sandbox.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.sandbox.id
  }

  tags = { Name = "civiform-sandbox-public-rt" }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.sandbox.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.sandbox.id
  }

  tags = { Name = "civiform-sandbox-private-rt" }
}

resource "aws_route_table_association" "private" {
  count          = 2
  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# ── Security Groups ───────────────────────────────────────────────────────────

# ALB: accept 80 + 443 from internet
resource "aws_security_group" "alb" {
  name        = "civiform-sandbox-alb"
  description = "ALB ingress from internet"
  vpc_id      = aws_vpc.sandbox.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "civiform-sandbox-alb-sg" }
}

# ECS tasks: accept traffic from ALB only
resource "aws_security_group" "ecs_tasks" {
  name        = "civiform-sandbox-ecs"
  description = "ECS sandbox tasks — ingress from ALB only"
  vpc_id      = aws_vpc.sandbox.id

  ingress {
    from_port       = 9000
    to_port         = 9000
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "civiform-sandbox-ecs-sg" }
}

# RDS: accept traffic from ECS tasks only
resource "aws_security_group" "rds" {
  name        = "civiform-sandbox-rds"
  description = "Sandbox RDS — ingress from ECS tasks only"
  vpc_id      = aws_vpc.sandbox.id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.ecs_tasks.id]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "civiform-sandbox-rds-sg" }
}

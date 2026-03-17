# 1. Base Security Group: Allows SSH from your local machine and all outbound traffic
resource "aws_security_group" "base_sg" {
  name        = "log-pipeline-base-sg"
  description = "Allow SSH inbound and all outbound"
  vpc_id      = aws_vpc.main_vpc.id

  # SSH Access
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"] # Note: In a true production environment, you restrict this to your specific IP
  }

  # Allow all outbound internet access (needed to download Docker, Java, etc.)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "log-pipeline-base-sg"
  }
}

# 2. Internal VPC Security Group: Allows resources within the VPC to talk to each other
resource "aws_security_group" "internal_sg" {
  name        = "log-pipeline-internal-sg"
  description = "Allow all internal VPC traffic (Kafka, Postgres, Metrics)"
  vpc_id      = aws_vpc.main_vpc.id

  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [aws_vpc.main_vpc.cidr_block]
  }

  tags = {
    Name = "log-pipeline-internal-sg"
  }
}

# 3. Web/Metrics Security Group: Exposes Grafana and the ALB to the internet
resource "aws_security_group" "web_sg" {
  name        = "log-pipeline-web-sg"
  description = "Allow web and metrics traffic"
  vpc_id      = aws_vpc.main_vpc.id

  # HTTP for ALB
  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # Grafana Dashboard
  ingress {
    from_port   = 3000
    to_port     = 3000
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "log-pipeline-web-sg"
  }
}
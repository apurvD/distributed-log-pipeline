# 1. Fetch the latest Ubuntu 22.04 LTS AMI automatically
data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical's official AWS account ID

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd/ubuntu-jammy-22.04-amd64-server-*"]
  }
}

# 2. Upload our public SSH key to AWS
resource "aws_key_pair" "pipeline_key" {
  key_name   = "log-pipeline-key"
  public_key = file("${path.module}/log-pipeline-key.pub")
}

# 3. Instance 1: Kafka Broker
resource "aws_instance" "kafka_node" {
  ami           = data.aws_ami.ubuntu.id
  instance_type = "t3.micro"
  subnet_id     = aws_subnet.public_subnet_a.id
  key_name      = aws_key_pair.pipeline_key.key_name

  vpc_security_group_ids = [
    aws_security_group.base_sg.id,
    aws_security_group.internal_sg.id
  ]

  root_block_device {
    volume_size = 8 # Keeps us under the 30GB Free Tier limit
    volume_type = "gp3"
  }

  tags = {
    Name = "log-pipeline-kafka"
  }
}

# 4. Instance 2: Producer App
resource "aws_instance" "producer_node" {
  ami           = data.aws_ami.ubuntu.id
  instance_type = "t3.micro"
  subnet_id     = aws_subnet.public_subnet_a.id
  key_name      = aws_key_pair.pipeline_key.key_name

  vpc_security_group_ids = [
    aws_security_group.base_sg.id,
    aws_security_group.internal_sg.id
  ]

  root_block_device {
    volume_size = 8
    volume_type = "gp3"
  }

  tags = {
    Name = "log-pipeline-producer"
  }
}

# 5. Instance 3: Consumer App + Database + Monitoring
resource "aws_instance" "consumer_node" {
  ami           = data.aws_ami.ubuntu.id
  instance_type = "t3.micro"
  subnet_id     = aws_subnet.public_subnet_b.id
  key_name      = aws_key_pair.pipeline_key.key_name

  vpc_security_group_ids = [
    aws_security_group.base_sg.id,
    aws_security_group.internal_sg.id,
    aws_security_group.web_sg.id # Needs web access for Grafana
  ]

  root_block_device {
    volume_size = 8
    volume_type = "gp3"
  }

  tags = {
    Name = "log-pipeline-consumer"
  }
}

# 6. Output the public IP addresses so we can connect to them
output "kafka_public_ip" {
  value = aws_instance.kafka_node.public_ip
}

output "producer_public_ip" {
  value = aws_instance.producer_node.public_ip
}

output "consumer_public_ip" {
  value = aws_instance.consumer_node.public_ip
}
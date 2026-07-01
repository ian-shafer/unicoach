# Latest Amazon Linux 2023 ARM (Graviton) AMI. The SSM agent is preinstalled, so
# the instance is reachable through Session Manager / Run Command with no SSH.
data "aws_ami" "al2023_arm" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-arm64"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

resource "aws_instance" "app" {
  ami                    = data.aws_ami.al2023_arm.id
  instance_type          = var.instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.instance.name

  # Public IP so outbound egress (Anthropic, SES, packages) routes directly
  # through the internet gateway, avoiding a NAT gateway's recurring charges. The
  # ec2 security group's only ingress is 8080 from the ALB, and no rule opens SSH.
  associate_public_ip_address = true

  user_data = templatefile("${path.module}/files/cloud-init.yaml", {
    ssm_prefix  = local.ssm_prefix
    region      = var.region
    module_path = path.module
  })

  tags = {
    Name = local.name_prefix
  }
}

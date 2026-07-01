data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "instance" {
  name               = "${local.name_prefix}-instance"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

# SSM Session Manager + Run Command (all administration and deployment).
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.instance.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Scoped read of the runtime config/secrets under the env's SSM prefix and decrypt
# of the SSM KMS key, plus read of the deploy-artifacts bucket and SES send.
data "aws_iam_policy_document" "instance" {
  statement {
    sid     = "ReadEnvParameters"
    actions = ["ssm:GetParametersByPath", "ssm:GetParameters", "ssm:GetParameter"]
    # GetParametersByPath authorizes against the path node itself (the ssm_prefix),
    # while GetParameter(s) authorize against each child (ssm_prefix/*). Both ARNs
    # are required: the wildcard alone denies the recursive path fetch.
    resources = [
      "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}",
      "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter${local.ssm_prefix}/*",
    ]
  }

  statement {
    sid       = "DecryptSsmSecrets"
    actions   = ["kms:Decrypt"]
    resources = [data.aws_kms_key.ssm.arn]
  }

  # SES send for transactional email (email.provider = "ses" in prod). The sender
  # identity itself is verified out-of-band; this only grants the send action.
  statement {
    sid       = "SendEmail"
    actions   = ["ses:SendEmail", "ses:SendRawEmail"]
    resources = ["*"]
  }

  statement {
    sid     = "ReadArtifacts"
    actions = ["s3:GetObject", "s3:ListBucket"]
    resources = [
      aws_s3_bucket.artifacts.arn,
      "${aws_s3_bucket.artifacts.arn}/*",
    ]
  }
}

resource "aws_iam_role_policy" "instance" {
  name   = "${local.name_prefix}-instance"
  role   = aws_iam_role.instance.id
  policy = data.aws_iam_policy_document.instance.json
}

resource "aws_iam_instance_profile" "instance" {
  name = "${local.name_prefix}-instance"
  role = aws_iam_role.instance.name
}

# The AWS-managed key SSM uses to encrypt SecureString parameters by default.
data "aws_kms_key" "ssm" {
  key_id = "alias/aws/ssm"
}

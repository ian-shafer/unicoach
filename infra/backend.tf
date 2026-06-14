terraform {
  backend "s3" {
    bucket       = "unicoach-tofu-state"
    key          = "unicoach/prod/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

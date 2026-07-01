terraform {
  # Partial backend: the per-env state key is supplied at init time by
  # bin/infra-* via -backend-config="key=unicoach/<env>/terraform.tfstate", so
  # each env's state is disjoint and prod is never a silent default. The bucket
  # is shared (one state store, keyed per env).
  backend "s3" {
    bucket       = "unicoach-tofu-state"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}

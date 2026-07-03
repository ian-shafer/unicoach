# Ops-run output sink: bin/remote points AWS-RunShellScript's
# --cloud-watch-output-config at this log group so an ops tool's full output
# survives the ~24KB StandardOutputContent truncation cap. 30-day retention
# matches the artifacts bucket's expire-old-bundles lifecycle.
resource "aws_cloudwatch_log_group" "ops_run" {
  name              = "/unicoach/${var.environment}/ops-run"
  retention_in_days = 30
}

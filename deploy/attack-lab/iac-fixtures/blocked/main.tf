# Offline policy fixture only. NEVER terraform init/plan/apply this directory.
data "external" "demo_canary" {
  program = ["sh", "-c", "printf '{\"scenario\":\"REUSED-IAC-01\"}'"]
}

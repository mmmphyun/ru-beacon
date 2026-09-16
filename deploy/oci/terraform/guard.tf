# 1. Always Free 상한선 하드 차단 Quota Policy
resource "oci_limits_quota" "always_free_guard" {
  compartment_id = var.tenancy_ocid
  name           = "always-free-guard"
  description    = "Always Free 초과 리소스 생성 차단 정책"

  statements = [
    "set compute-core quota standard-a1-core-count to 4 in tenancy",
    "set compute-memory quota standard-a1-memory-count to 24 in tenancy",
    "set block-storage quota total-storage-gb to 200 in tenancy",
    "set vcn quota reserved-public-ip-count to 0 in tenancy"
  ]
}

# 2. 1달러(약 1,400원) 예산 초과 감시 Budget
resource "oci_budget_budget" "always_free_budget" {
  compartment_id = var.tenancy_ocid
  amount         = 1
  reset_period   = "MONTHLY"
  display_name   = "always-free-budget-guard"
  target_type    = "TENANCY"
  targets        = [var.tenancy_ocid]
}

# 3. 예산 100% 도달 시 즉시 발송되는 알림 룰
resource "oci_budget_rule" "spend_alert_rule" {
  budget_id      = oci_budget_budget.always_free_budget.id
  display_name   = "spend-alert-rule"
  threshold      = 100
  threshold_type = "PERCENTAGE"
  type           = "ACTUAL"
  recipients     = var.alert_email
  description    = "예기치 않은 유료 과금 발생 시 즉각 엔지니어 경보 발송"
}
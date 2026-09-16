variable "tenancy_ocid" {
  description = "OCI 테넌시 OCID"
  type        = string
}

variable "user_ocid" {
  description = "OCI IAM 사용자 OCID"
  type        = string
}

variable "fingerprint" {
  description = "OCI API Key 지문 (Fingerprint)"
  type        = string
}

variable "private_key_path" {
  description = "OCI API Private Key 로컬 경로"
  type        = string
  default     = "~/.oci/oci_api_key.pem"
}

variable "region" {
  description = "배포 대상 OCI 홈 리전 (예: ap-osaka-1)"
  type        = string
  default     = "ap-osaka-1"
}

variable "compartment_ocid" {
  description = "인프라가 배포될 구획 OCID (보통 루트 테넌시 OCID와 동일)"
  type        = string
}

variable "ssh_public_key" {
  description = "인스턴스에 주입할 SSH 공개키 본문"
  type        = string
}

variable "alert_email" {
  description = "예산 초과 시 알림을 수신할 엔지니어 이메일 주소"
  type        = string
}
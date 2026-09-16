# OCI 2-Node K3s Infrastructure as Code (Terraform)

Ru-Beacon 클라우드 알파 배포를 위한 OCI(Oracle Cloud Infrastructure) Always Free 2-Node K3s 인프라 및 FinOps 과금 방어선 자동화 명세입니다.

---

## 1. 아키텍처 개요

- **네트워크 (`network.tf`)**:
  - CIDR `10.0.0.0/16` 기반 `rubeacon-vcn` 및 퍼블릭 서브넷 (`10.0.0.0/24`)
  - K3s CNI(Flannel VXLAN `UDP 8472`, Kubelet `TCP 10250`) 내부 전면 허용
  - Ingress(`TCP 80, 443`), API Server(`TCP 6443`), SSH(`TCP 22`) 보안 목록
- **컴퓨트 노드 (`compute.tf`)**:
  - `Node 1 (Master)`: Ampere A1 (2 OCPU, 12GB RAM) - Control Plane + Ingress + Core API
  - `Node 2 (Worker)`: Ampere A1 (2 OCPU, 12GB RAM) - Queue Worker + Discord Bot + Postgres/Redis
  - `lifecycle { prevent_destroy = true }` 하드 가드를 통한 휴먼 에러 및 슬롯 유실 방지
- **FinOps 과금 방어선 (`guard.tf`)**:
  - **Quota Policy**: A1 코어(4개), 메모리(24GB), 스토리지(200GB) 초과 생성 원천 차단 (Hard Limit)
  - **Budget Alert**: 1달러(약 1,400원) 도달 시 즉시 엔지니어 경보 발송

---

## 2. 사용 방법

```bash
# 1. 변수 파일 복사 및 자격증명 입력
cp terraform.tfvars.example terraform.tfvars

# 2. 초기화 및 검증
terraform init
terraform plan

# 3. 배포 실행
terraform apply
```
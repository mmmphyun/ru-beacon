resource "oci_core_vcn" "rubeacon_vcn" {
  compartment_id = var.compartment_ocid
  cidr_blocks    = ["10.0.0.0/16"]
  display_name   = "rubeacon-vcn"
  dns_label      = "rubeacon"
}

resource "oci_core_internet_gateway" "rubeacon_igw" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.rubeacon_vcn.id
  display_name   = "rubeacon-igw"
  enabled        = true
}

resource "oci_core_default_route_table" "rubeacon_route_table" {
  manage_default_resource_id = oci_core_vcn.rubeacon_vcn.default_route_table_id

  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.rubeacon_igw.id
  }
}

resource "oci_core_security_list" "rubeacon_security_list" {
  compartment_id = var.compartment_ocid
  vcn_id         = oci_core_vcn.rubeacon_vcn.id
  display_name   = "rubeacon-security-list"

  # 아웃바운드 전체 허용
  egress_security_rules {
    destination = "0.0.0.0/0"
    protocol    = "all"
    description = "전체 외부 아웃바운드 트래픽 허용"
  }

  # 1. SSH 관리 포트
  ingress_security_rules {
    protocol    = "6" # TCP
    source      = "0.0.0.0/0"
    description = "SSH 원격 접속 포트"

    tcp_options {
      min = 22
      max = 22
    }
  }

  # 2. 외부 웹 대시보드(HTTP) 및 WSS(HTTPS) 인그레스
  ingress_security_rules {
    protocol    = "6"
    source      = "0.0.0.0/0"
    description = "HTTP Ingress"

    tcp_options {
      min = 80
      max = 80
    }
  }

  ingress_security_rules {
    protocol    = "6"
    source      = "0.0.0.0/0"
    description = "HTTPS/WSS Ingress"

    tcp_options {
      min = 443
      max = 443
    }
  }

  # 3. K3s Kubernetes API Server 포트
  ingress_security_rules {
    protocol    = "6"
    source      = "0.0.0.0/0"
    description = "K3s API Server 원격 제어 포트"

    tcp_options {
      min = 6443
      max = 6443
    }
  }

  # 4. K3s Flannel CNI(VXLAN 8472) 및 Kubelet(10250) 내부 사설망 전면 허용
  ingress_security_rules {
    protocol    = "all"
    source      = "10.0.0.0/16"
    description = "노드 간 K3s CNI(Flannel VXLAN/Kubelet) 내부 통신"
  }
}

resource "oci_core_subnet" "rubeacon_public_subnet" {
  compartment_id             = var.compartment_ocid
  vcn_id                     = oci_core_vcn.rubeacon_vcn.id
  cidr_block                 = "10.0.0.0/24"
  display_name               = "public-subnet-rubeacon"
  dns_label                  = "public"
  security_list_ids          = [oci_core_security_list.rubeacon_security_list.id]
  route_table_id             = oci_core_default_route_table.rubeacon_route_table.id
  prohibit_public_ip_on_vnic = false
}
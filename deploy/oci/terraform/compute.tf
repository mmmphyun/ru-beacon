data "oci_identity_availability_domains" "ads" {
  compartment_id = var.compartment_ocid
}

# Ubuntu 24.04 aarch64 이미지 자동 조회
data "oci_core_images" "ubuntu_arm" {
  compartment_id           = var.compartment_ocid
  operating_system         = "Canonical Ubuntu"
  operating_system_version = "24.04 Minimal aarch64"
  shape                    = "VM.Standard.A1.Flex"
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

# Node 1: Master (Control Plane + Ingress + Core App)
resource "oci_core_instance" "node_1" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "rubeacon-node-1"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = 2
    memory_in_gbs = 12
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ubuntu_arm.images[0].id
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.rubeacon_public_subnet.id
    assign_public_ip = true
    display_name     = "rubeacon-node-1-vnic"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = filebase64("${path.module}/../cloud-init.sh")
  }

  lifecycle {
    prevent_destroy = true
    ignore_changes = [
      source_details[0].source_id
    ]
  }
}

# Node 2: Worker (Queue Worker + Discord Bot + Postgres/Redis)
resource "oci_core_instance" "node_2" {
  compartment_id      = var.compartment_ocid
  availability_domain = data.oci_identity_availability_domains.ads.availability_domains[0].name
  display_name        = "rubeacon-node-2"
  shape               = "VM.Standard.A1.Flex"

  shape_config {
    ocpus         = 2
    memory_in_gbs = 12
  }

  source_details {
    source_type = "image"
    source_id   = data.oci_core_images.ubuntu_arm.images[0].id
  }

  create_vnic_details {
    subnet_id        = oci_core_subnet.rubeacon_public_subnet.id
    assign_public_ip = true
    display_name     = "rubeacon-node-2-vnic"
  }

  metadata = {
    ssh_authorized_keys = var.ssh_public_key
    user_data           = filebase64("${path.module}/../cloud-init.sh")
  }

  lifecycle {
    prevent_destroy = true
    ignore_changes = [
      source_details[0].source_id
    ]
  }
}
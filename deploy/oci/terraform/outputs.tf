output "node_1_public_ip" {
  description = "Master Node (Node 1) 공용 IP"
  value       = oci_core_instance.node_1.public_ip
}

output "node_1_private_ip" {
  description = "Master Node (Node 1) 사설 IP"
  value       = oci_core_instance.node_1.private_ip
}

output "node_2_public_ip" {
  description = "Worker Node (Node 2) 공용 IP"
  value       = oci_core_instance.node_2.public_ip
}

output "node_2_private_ip" {
  description = "Worker Node (Node 2) 사설 IP"
  value       = oci_core_instance.node_2.private_ip
}
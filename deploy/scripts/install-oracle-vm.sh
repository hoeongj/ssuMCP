#!/usr/bin/env bash
set -euo pipefail

sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable

curl -sfL https://get.k3s.io | sh -
sudo k3s kubectl get nodes

echo
echo "k3s installed."
echo "Copy this kubeconfig to your laptop and replace 127.0.0.1 with the VM public IP:"
echo "  sudo cat /etc/rancher/k3s/k3s.yaml"

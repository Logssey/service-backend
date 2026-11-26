#!/usr/bin/env bash
set -euo pipefail
namespace=reused-attack-lab
label=$(kubectl get namespace "$namespace" -o 'jsonpath={.metadata.labels.logssey\.io/environment}')
[[ "$label" == attack-lab ]] || { echo 'Refusing cleanup: unexpected namespace'; exit 1; }
kubectl delete namespace "$namespace" --wait=true --timeout=180s

#!/usr/bin/env bash
set -euo pipefail
namespace=reused-attack-lab
label=$(kubectl get namespace "$namespace" -o 'jsonpath={.metadata.labels.logssey\.io/environment}')
[[ "$label" == attack-lab ]] || { echo 'Refusing cleanup: unexpected namespace'; exit 1; }
if [[ -n "$(kubectl -n reach get cronjob reach-l1-demo-collector --ignore-not-found -o name)" ]]; then
  echo 'Remove the demo collector and lab RBAC from GitOps first, then wait for reach-l1 to sync. Do not delete the production collector.'
  exit 1
fi
if [[ -n "$(kubectl -n "$namespace" get rolebinding reach-l1-pod-list --ignore-not-found -o name)" ]]; then
  echo 'Lab RBAC is still managed by GitOps; retire it before namespace cleanup.'
  exit 1
fi
kubectl delete namespace "$namespace" --wait=true --timeout=180s

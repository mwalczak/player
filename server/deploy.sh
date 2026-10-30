#!/usr/bin/env bash
# Runs ON THE VPS (piped over ssh by .github/workflows/sync-server.yml).
# Deploys the Bunny Player sync service to k3s at https://$SYNC_HOST, with a small volume for its
# SQLite file. The ingress class, annotations and TLS setup are copied from an ingress that already
# works on this cluster (PicoShare's in namespace "files"), so it gets HTTPS the same way.
#
# Usage: deploy.sh <path to sync_server.py>
# Env overrides: SYNC_HOST (bunny.walczaki.com), SYNC_NAMESPACE (bunny), REF_NAMESPACE (files)
set -euo pipefail

SERVER_PY="$1"
HOST="${SYNC_HOST:-bunny.walczaki.com}"
NS="${SYNC_NAMESPACE:-bunny}"
REF_NS="${REF_NAMESPACE:-files}"

# Non-interactive ssh sessions skip ~/.bashrc, so pick up the KUBECONFIG an interactive shell would use,
# then fall back to sudo (k3s keeps /etc/rancher/k3s/k3s.yaml root-only by default).
if [[ -z "${KUBECONFIG:-}" ]]; then
  kc="$(grep -hoE 'KUBECONFIG=[^ ;]+' ~/.bashrc ~/.profile ~/.bash_profile ~/.zshrc 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"'" || true)"
  kc="${kc/#\~/$HOME}"
  kc="${kc//\$HOME/$HOME}"
  [[ -n "$kc" && -r "$kc" ]] && export KUBECONFIG="$kc"
fi
if [[ -z "${KUBECONFIG:-}" && ! -f "$HOME/.kube/config" && -r /etc/rancher/k3s/k3s.yaml ]]; then
  export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
fi
KUBECTL=(command kubectl)
if ! command kubectl get namespace "$REF_NS" >/dev/null 2>&1; then
  if sudo -n k3s kubectl get namespace "$REF_NS" >/dev/null 2>&1; then
    KUBECTL=(sudo -n k3s kubectl)
  elif sudo -n kubectl get namespace "$REF_NS" >/dev/null 2>&1; then
    KUBECTL=(sudo -n kubectl)
  else
    echo "kubectl cannot reach the cluster as $(whoami) (tried KUBECONFIG, ~/.kube/config and passwordless sudo)." >&2
    exit 1
  fi
fi
echo "Using: ${KUBECTL[*]}"
kubectl() { "${KUBECTL[@]}" "$@"; }

[[ -f "$SERVER_PY" ]] || { echo "Server script not found: $SERVER_PY" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
kubectl get ingress -n "$REF_NS" -o json > "$work/ref.json"

# Builds every object as one JSON list; prints the TLS secret to copy (if the reference uses a
# wildcard certificate) on the first line of $work/copy-secret.
python3 - "$SERVER_PY" "$HOST" "$NS" "$work/ref.json" "$work/copy-secret" > "$work/manifest.json" <<'PY'
import hashlib, json, sys
server_py, host, ns, ref_path, copy_path = sys.argv[1:6]
source = open(server_py).read()
refs = json.load(open(ref_path)).get("items", [])
if not refs:
    sys.exit("No ingress found in the reference namespace to copy the HTTPS setup from.")
ref = refs[0]
spec = ref.get("spec", {})
skip = ("kubectl.kubernetes.io/", "field.cattle.io/", "meta.helm.sh/")
annotations = {k: v for k, v in (ref["metadata"].get("annotations") or {}).items() if not k.startswith(skip)}

def covers(pattern, name):
    return pattern == name or (pattern.startswith("*.") and name.endswith(pattern[1:]) and name.count(".") == pattern.count("."))

tls = []
copy_secret = ""
for entry in spec.get("tls") or []:
    if any(covers(h, host) for h in entry.get("hosts", [])) and entry.get("secretName"):
        # A wildcard certificate that already covers our host: reuse it.
        copy_secret = entry["secretName"]
        tls = [{"hosts": [host], "secretName": copy_secret}]
        break
if not tls and spec.get("tls"):
    # Same issuer (annotations), our own certificate.
    tls = [{"hosts": [host], "secretName": "sync-server-tls"}]
open(copy_path, "w").write(copy_secret)

labels = {"app": "sync-server"}
items = [
    {"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": ns}},
    {"apiVersion": "v1", "kind": "ConfigMap", "metadata": {"name": "sync-server", "namespace": ns},
     "data": {"sync_server.py": source}},
    {"apiVersion": "v1", "kind": "PersistentVolumeClaim", "metadata": {"name": "sync-data", "namespace": ns},
     "spec": {"accessModes": ["ReadWriteOnce"], "resources": {"requests": {"storage": "1Gi"}}}},
    {"apiVersion": "apps/v1", "kind": "Deployment", "metadata": {"name": "sync-server", "namespace": ns, "labels": labels},
     "spec": {
         "replicas": 1,
         "strategy": {"type": "Recreate"},  # one writer for the SQLite file
         "selector": {"matchLabels": labels},
         "template": {
             "metadata": {"labels": labels,
                          "annotations": {"bunny/source-sha": hashlib.sha256(source.encode()).hexdigest()[:16]}},
             "spec": {
                 "containers": [{
                     "name": "sync-server",
                     "image": "python:3.12-alpine",
                     "command": ["python3", "-u", "/app/sync_server.py"],
                     "env": [{"name": "SYNC_DB", "value": "/data/sync.db"}, {"name": "PORT", "value": "8080"}],
                     "ports": [{"containerPort": 8080}],
                     "readinessProbe": {"httpGet": {"path": "/healthz", "port": 8080}, "periodSeconds": 10},
                     "livenessProbe": {"httpGet": {"path": "/healthz", "port": 8080}, "periodSeconds": 30},
                     "resources": {"requests": {"cpu": "10m", "memory": "32Mi"}, "limits": {"memory": "128Mi"}},
                     "volumeMounts": [{"name": "app", "mountPath": "/app"}, {"name": "data", "mountPath": "/data"}],
                 }],
                 "volumes": [
                     {"name": "app", "configMap": {"name": "sync-server"}},
                     {"name": "data", "persistentVolumeClaim": {"claimName": "sync-data"}},
                 ],
             },
         },
     }},
    {"apiVersion": "v1", "kind": "Service", "metadata": {"name": "sync-server", "namespace": ns},
     "spec": {"selector": labels, "ports": [{"port": 80, "targetPort": 8080}]}},
    {"apiVersion": "networking.k8s.io/v1", "kind": "Ingress",
     "metadata": {"name": "sync-server", "namespace": ns, "annotations": annotations},
     "spec": dict(
         ({"ingressClassName": spec["ingressClassName"]} if spec.get("ingressClassName") else {}),
         rules=[{"host": host, "http": {"paths": [{"path": "/", "pathType": "Prefix", "backend": {
             "service": {"name": "sync-server", "port": {"number": 80}}}}]}}],
         **({"tls": tls} if tls else {}),
     )},
]
print(json.dumps({"apiVersion": "v1", "kind": "List", "items": items}))
print("Reference ingress: %s/%s, class %s, tls %s" % (
    ref["metadata"]["namespace"], ref["metadata"]["name"], spec.get("ingressClassName"), tls or "none"), file=sys.stderr)
PY

kubectl apply -f "$work/manifest.json"

secret="$(cat "$work/copy-secret")"
if [[ -n "$secret" ]]; then
  echo "Reusing wildcard certificate $REF_NS/$secret"
  kubectl get secret -n "$REF_NS" "$secret" -o json \
    | python3 -c 'import json,sys; s=json.load(sys.stdin); m=s["metadata"]; s["metadata"]={"name":m["name"],"namespace":sys.argv[1]}; print(json.dumps(s))' "$NS" \
    | kubectl apply -f -
fi

if ! kubectl rollout status -n "$NS" deploy/sync-server --timeout=180s; then
  kubectl get pods,pvc -n "$NS" -o wide || true
  kubectl describe pods -n "$NS" -l app=sync-server | tail -40 || true
  exit 1
fi

# HTTPS may need a minute for a new certificate.
for _ in $(seq 1 24); do
  if out="$(curl -fsS --max-time 10 "https://$HOST/healthz" 2>&1)"; then
    echo "SYNC_OK https://$HOST/healthz -> $out"
    exit 0
  fi
  sleep 5
done
echo "Deployed, but https://$HOST/healthz isn't answering yet: $out" >&2
kubectl get ingress,certificate -n "$NS" 2>/dev/null || kubectl get ingress -n "$NS"
exit 1

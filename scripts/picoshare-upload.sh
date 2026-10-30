#!/usr/bin/env bash
# Runs ON THE VPS (piped over ssh by the GitHub workflow).
# Uploads an APK to the PicoShare instance running in Kubernetes, then swaps it into the existing
# entry with the same filename so that entry's share link stays the same and serves the new build.
#
# PicoShare has no "replace file" API and stores file contents as chunks inside its SQLite DB,
# so the swap is done with sqlite3 inside the pod: the new chunks are moved under the old entry's
# ID and the temporary entry is deleted.
#
# Usage: picoshare-upload.sh <apk-path> <note> [expire-days] [target-filename]
# Env overrides: PICOSHARE_NAMESPACE (default "files"), PICOSHARE_DEPLOYMENT (default "picoshare"),
#                PICOSHARE_SECRET (otherwise read from the running pod).
set -euo pipefail

APK="$1"
NOTE="${2:-}"
EXPIRE_DAYS="${3:-365}"
TARGET_NAME="${4:-android.all.apk}"
NS="${PICOSHARE_NAMESPACE:-files}"
DEPLOY="${PICOSHARE_DEPLOYMENT:-picoshare}"
LOCAL_PORT=14001

# Non-interactive ssh sessions skip ~/.bashrc, so pick up the KUBECONFIG an interactive shell would use,
# then fall back to sudo (k3s keeps /etc/rancher/k3s/k3s.yaml root-only by default).
if [[ -z "${KUBECONFIG:-}" ]]; then
  kc="$(grep -hoE 'KUBECONFIG=[^ ;]+' ~/.bashrc ~/.profile ~/.bash_profile ~/.zshrc 2>/dev/null | tail -1 | cut -d= -f2- | tr -d "\"'")"
  kc="${kc/#\~/$HOME}"
  kc="${kc//\$HOME/$HOME}"
  [[ -n "$kc" && -r "$kc" ]] && export KUBECONFIG="$kc"
fi
if [[ -z "${KUBECONFIG:-}" && ! -f "$HOME/.kube/config" && -r /etc/rancher/k3s/k3s.yaml ]]; then
  export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
fi

KUBECTL=(command kubectl)
if ! command kubectl get namespace "$NS" >/dev/null 2>&1; then
  if sudo -n k3s kubectl get namespace "$NS" >/dev/null 2>&1; then
    KUBECTL=(sudo -n k3s kubectl)
  elif sudo -n kubectl get namespace "$NS" >/dev/null 2>&1; then
    KUBECTL=(sudo -n kubectl)
  else
    echo "kubectl cannot reach the cluster as $(whoami) (tried KUBECONFIG, ~/.kube/config and passwordless sudo)." >&2
    echo "Fix: mkdir -p ~/.kube && sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config && sudo chown \$USER ~/.kube/config" >&2
    exit 1
  fi
fi
echo "Using: ${KUBECTL[*]}"
kubectl() { "${KUBECTL[@]}" "$@"; }

[[ -f "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }

# --- Shared secret: env > pod environment > deployment spec / k8s secret -----------------------
secret="${PICOSHARE_SECRET:-}"
if [[ -z "$secret" ]]; then
  secret="$(kubectl exec -n "$NS" "deploy/$DEPLOY" -- printenv PS_SHARED_SECRET 2>/dev/null || true)"
fi
if [[ -z "$secret" ]]; then
  env_json="$(kubectl get deploy -n "$NS" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PS_SHARED_SECRET")]}')"
  secret="$(python3 -c 'import json,sys; d=json.loads(sys.argv[1] or "{}"); print(d.get("value",""))' "$env_json" 2>/dev/null || true)"
  if [[ -z "$secret" ]]; then
    ref_name="$(kubectl get deploy -n "$NS" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PS_SHARED_SECRET")].valueFrom.secretKeyRef.name}')"
    ref_key="$(kubectl get deploy -n "$NS" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="PS_SHARED_SECRET")].valueFrom.secretKeyRef.key}')"
    if [[ -n "$ref_name" && -n "$ref_key" ]]; then
      secret="$(kubectl get secret -n "$NS" "$ref_name" -o "jsonpath={.data.$ref_key}" | base64 -d)"
    fi
  fi
fi
[[ -n "$secret" ]] || { echo "Could not determine PicoShare PS_SHARED_SECRET" >&2; exit 1; }

# --- Reach the pod through a temporary port-forward ---------------------------------------------
port="$(kubectl get deploy -n "$NS" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].ports[0].containerPort}')"
port="${port:-4001}"

kubectl port-forward -n "$NS" "deploy/$DEPLOY" "$LOCAL_PORT:$port" >/tmp/picoshare-pf.log 2>&1 &
pf_pid=$!
cookies="$(mktemp)"
trap 'kill $pf_pid 2>/dev/null || true; rm -f "$cookies"' EXIT

base="http://127.0.0.1:$LOCAL_PORT"
for _ in $(seq 1 30); do
  curl -s -o /dev/null "$base/login" && break
  sleep 1
done

# --- Authenticate and upload --------------------------------------------------------------------
auth_body="$(python3 -c 'import json,sys; print(json.dumps({"sharedSecretKey": sys.argv[1]}))' "$secret")"
curl -sf -c "$cookies" -H 'Content-Type: application/json' -d "$auth_body" "$base/api/auth" >/dev/null \
  || { echo "PicoShare authentication failed" >&2; exit 1; }

expiration="$(date -u -d "+${EXPIRE_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)"
response="$(curl -sf -b "$cookies" \
  -F "file=@${APK};filename=${TARGET_NAME};type=application/vnd.android.package-archive" \
  -F "note=${NOTE}" \
  "$base/api/entry?expiration=${expiration}")" \
  || { echo "PicoShare upload failed" >&2; exit 1; }

new_id="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$response")"
[[ "$new_id" =~ ^[A-Za-z0-9]+$ ]] || { echo "Unexpected entry id: $new_id" >&2; exit 1; }

# --- Swap the upload into the existing entry ----------------------------------------------------
db="$(kubectl get deploy -n "$NS" "$DEPLOY" -o jsonpath='{.spec.template.spec.containers[0].args}' \
  | python3 -c 'import json,sys; a=json.loads(sys.stdin.read() or "[]"); print(a[a.index("-db")+1] if "-db" in a else "/data/store.db")' 2>/dev/null \
  || echo /data/store.db)"

pod_sql() {
  # The image is Alpine and runs as root; sqlite3 is added on first use (lost on pod restart, harmless).
  kubectl exec -i -n "$NS" "deploy/$DEPLOY" -- sh -c \
    'command -v sqlite3 >/dev/null 2>&1 || apk add --no-cache sqlite >/dev/null 2>&1; exec sqlite3 -batch -noheader -bail "$0"' "$db"
}

target_sql="$(python3 -c 'import sys; print(sys.argv[1].replace("\x27", "\x27\x27"))' "$TARGET_NAME")"
old_id="$(printf "SELECT id FROM entries WHERE filename = '%s' AND id <> '%s' ORDER BY upload_time DESC LIMIT 1;\n" \
  "$target_sql" "$new_id" | pod_sql | tr -d '[:space:]')"

if [[ -z "$old_id" ]]; then
  echo "No existing '$TARGET_NAME' entry - keeping the new one as the stable entry."
  final_id="$new_id"
else
  [[ "$old_id" =~ ^[A-Za-z0-9]+$ ]] || { echo "Unexpected existing entry id: $old_id" >&2; exit 1; }
  pod_sql <<SQL
.timeout 15000
BEGIN IMMEDIATE;
DELETE FROM entries_data WHERE id = '$old_id';
UPDATE entries_data SET id = '$old_id' WHERE id = '$new_id';
UPDATE entries SET
  content_type    = (SELECT content_type    FROM entries WHERE id = '$new_id'),
  upload_time     = (SELECT upload_time     FROM entries WHERE id = '$new_id'),
  expiration_time = (SELECT expiration_time FROM entries WHERE id = '$new_id'),
  note            = (SELECT note            FROM entries WHERE id = '$new_id')
WHERE id = '$old_id';
DELETE FROM downloads WHERE entry_id = '$new_id';
DELETE FROM entries WHERE id = '$new_id';
COMMIT;
SQL
  echo "Replaced contents of existing entry $old_id."
  final_id="$old_id"
fi

host="$(kubectl get ingress -n "$NS" -o jsonpath='{.items[0].spec.rules[0].host}' 2>/dev/null || true)"
if [[ -n "$host" ]]; then
  echo "PICOSHARE_URL=https://$host/-$final_id"
else
  echo "PICOSHARE_URL=/-$final_id"
fi

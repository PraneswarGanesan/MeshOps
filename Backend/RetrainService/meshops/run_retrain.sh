#!/bin/bash
set -euo pipefail
# Usage: run_retrain.sh JOB_ID S3_REPORT [datasets...]
JOB_ID=$1
S3_REPORT=$2
shift 2
DATASETS=("$@")

WORKDIR="/opt/meshops/work_${JOB_ID}"
mkdir -p "$WORKDIR"

# Local log file
LOG_FILE="$WORKDIR/train.log"
: > "$LOG_FILE"

echo "[AAAR+] Launching retrain job $JOB_ID" | tee -a "$LOG_FILE"

# Build datasets args (quote properly)
DATASET_ARGS=""
for d in "${DATASETS[@]}"; do
  DATASET_ARGS+=" \"$d\""
done

# Run retrain.py and stream stdout/stderr to log (preserve exit code)
python3 /opt/meshops/retrain.py \
  --job_id "$JOB_ID" \
  --s3_report "$S3_REPORT" \
  --datasets $DATASET_ARGS \
  2>&1 | tee -a "$LOG_FILE"
RC=${PIPESTATUS[0]:-0}

if command -v aws >/dev/null 2>&1; then
  # Upload raw log right next to retrain_report.json
  aws s3 cp "$LOG_FILE" "${S3_REPORT}.log" || true

  # Derive bucket/prefix from S3_REPORT
  BUCKET=$(echo "$S3_REPORT" | cut -d/ -f3)
  KEY=$(echo "$S3_REPORT" | cut -d/ -f4-)
  BASE_PREFIX=$(dirname "$KEY")

  # Also copy log into the version run folder (canonical)
  # Example: artifacts/versions/vN/runs/run_JOBID/logs.txt
  aws s3 cp "$LOG_FILE" "s3://$BUCKET/$BASE_PREFIX/logs.txt" || true
fi

exit $RC

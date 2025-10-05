#!/usr/bin/env bash
set -euxo pipefail

# ----------------------------
# Input arguments
# ----------------------------
JOB_ID="$1"                # Unique job ID
S3_REPORT="$2"             # S3 path for final retrain report
USER_REQ_FILE="$3"         # Optional path to user's custom requirements file (S3 or relative key)
shift 3
USER_PATHS="$@"            # Remaining args: user's code/data files

# ----------------------------
# Environment Variables
# ----------------------------
AWS_S3_BUCKET="${AWS_S3_BUCKET:-}"
AWS_REGION="${AWS_REGION:-us-east-1}"

# ----------------------------
# Directories
# ----------------------------
WORK_DIR="/jobs/job_${JOB_ID}"
VENV_DIR="${WORK_DIR}/venv"

mkdir -p "${WORK_DIR}"

echo "[INFO] === Starting retrain job: ${JOB_ID} ==="
echo "[INFO] Workspace: ${WORK_DIR}"
echo "[INFO] User requirements file: ${USER_REQ_FILE:-<none>}"
echo "[INFO] AWS_S3_BUCKET: ${AWS_S3_BUCKET:-<unset>}"
echo "[INFO] AWS_REGION: ${AWS_REGION}"

# ----------------------------
# 1. Create Python virtual environment
# ----------------------------
python3 -m venv "${VENV_DIR}"
source "${VENV_DIR}/bin/activate"

# ----------------------------
# 2. Upgrade pip inside venv
# ----------------------------
pip install --upgrade pip

# ----------------------------
# 3. Install base requirements from Automesh code package
# ----------------------------
if [ -f "/opt/meshops/requirements.txt" ]; then
    echo "[INFO] Installing base requirements..."
    pip install -r /opt/meshops/requirements.txt
else
    echo "[WARN] No base requirements.txt found in /opt/meshops"
fi

# ----------------------------
# 4. Handle user-provided requirements file
# ----------------------------
if [ -n "${USER_REQ_FILE}" ]; then
    echo "[INFO] Detected user requirements file: ${USER_REQ_FILE}"
    LOCAL_USER_REQ="${WORK_DIR}/user_requirements.txt"

    # If full S3 path provided
    if [[ "${USER_REQ_FILE}" == s3://* ]]; then
        SRC_PATH="${USER_REQ_FILE}"
    else
        # Treat as relative key — prepend bucket
        if [ -z "${AWS_S3_BUCKET}" ]; then
            echo "[ERROR] AWS_S3_BUCKET is not set; cannot fetch ${USER_REQ_FILE}"
            SRC_PATH=""
        else
            SRC_PATH="s3://${AWS_S3_BUCKET}/${USER_REQ_FILE}"
        fi
    fi

    if [ -n "${SRC_PATH}" ]; then
        echo "[INFO] Copying user requirements from ${SRC_PATH}"
        aws s3 cp "${SRC_PATH}" "${LOCAL_USER_REQ}" || echo "[WARN] Failed to fetch user requirements"
    fi

    if [ -f "${LOCAL_USER_REQ}" ]; then
        echo "[INFO] Installing user packages..."
        pip install -r "${LOCAL_USER_REQ}" || echo "[WARN] Failed to install some user packages"
    else
        echo "[WARN] No user requirements file found at ${USER_REQ_FILE}"
    fi
else
    echo "[INFO] No user requirements provided."
fi

# ----------------------------
# 5. Run retrain.py with provided user files
# ----------------------------
echo "[INFO] Running retrain.py ..."
python /opt/meshops/retrain.py \
    "${JOB_ID}" \
    "${S3_REPORT}" \
    ${USER_PATHS}

EXIT_CODE=$?

# ----------------------------
# 6. Cleanup to save disk space
# ----------------------------
echo "[INFO] Cleaning up workspace ${WORK_DIR}..."

# deactivate venv safely
deactivate || true

# remove entire job directory
rm -rf "${WORK_DIR:?}" || true

# clean pip & tmp caches
rm -rf ~/.cache/pip/* || true
rm -rf /root/.cache/pip/* || true
rm -rf /tmp/* || true
rm -rf /var/tmp/* || true

echo "[INFO] === Retrain job finished with exit code ${EXIT_CODE} ==="
exit ${EXIT_CODE}

#!/usr/bin/env python3
import os
import uuid
from datetime import datetime
from typing import Dict, Any, List, Optional

from fastapi import FastAPI, BackgroundTasks, HTTPException
from pydantic import BaseModel
from dotenv import load_dotenv

from aws_utils import (
    parse_s3_path,
    s3_key_exists,
    s3_get_json,
    run_command_via_ssm,
    get_ssm_command_output,
)
from ec2_utils import create_instance
import boto3

# --------------------------
# Load environment variables
# --------------------------
load_dotenv()

app = FastAPI(title="meshops-retrain-service", version="3.0")

# --------------------------
# AWS Configuration
# --------------------------
EC2_AMI = os.getenv("AWS_EC2_AMI")
EC2_INSTANCE_TYPE = os.getenv("AWS_EC2_INSTANCE_TYPE", "c5.large")
EC2_IAM_ROLE = os.getenv("AWS_EC2_IAM_PROFILE")
EC2_SECURITY_GROUPS = (
    os.getenv("AWS_EC2_SECURITY_GROUPS", "").split(",")
    if os.getenv("AWS_EC2_SECURITY_GROUPS")
    else []
)
EC2_SUBNET = os.getenv("AWS_EC2_SUBNET")
S3_BUCKET = os.getenv("AWS_S3_BUCKET")
AWS_REGION = os.getenv("AWS_REGION", "us-east-1")

# --------------------------
# In-memory Job Store
# --------------------------
jobs_store: Dict[str, Dict[str, Any]] = {}

# --------------------------
# API MODELS
# --------------------------
class RetrainRequest(BaseModel):
    username: str
    projectName: str
    files: List[str]                               # User’s training files
    saveBase: str                                  # Output directory in S3
    version: Optional[str] = None
    requirementsPath: Optional[str] = None         # User’s requirements file
    extraArgs: Optional[List[str]] = []            # Reserved for future use


class StatusView(BaseModel):
    jobId: str
    status: str
    s3_report: Optional[str] = None
    report: Optional[Dict[str, Any]] = None
    last_console: Optional[Dict[str, Any]] = None
    instance_id: Optional[str] = None
    command_id: Optional[str] = None


# --------------------------
# Helper Functions
# --------------------------
def _generate_job_id(username: str, project: str) -> str:
    ts = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    uid = uuid.uuid4().hex[:6]
    return f"{username}_{project}_{ts}_{uid}"


def _ensure_sandbox() -> str:
    ec2 = boto3.client("ec2", region_name=AWS_REGION)
    resp = ec2.describe_instances(
        Filters=[
            {"Name": "tag:Name", "Values": ["meshops-retrain-service"]},
            {"Name": "instance-state-name", "Values": ["pending", "running", "stopped"]},
        ]
    )

    if resp.get("Reservations"):
        inst = resp["Reservations"][0]["Instances"][0]
        inst_id = inst["InstanceId"]
        state = inst["State"]["Name"]

        if state == "stopped":
            ec2.start_instances(InstanceIds=[inst_id])
            ec2.get_waiter("instance_running").wait(InstanceIds=[inst_id])
        return inst_id

    res = create_instance(
        ami_id=EC2_AMI,
        instance_type=EC2_INSTANCE_TYPE,
        sg_ids=EC2_SECURITY_GROUPS,
        subnet_id=EC2_SUBNET,
        iam_profile=EC2_IAM_ROLE,
        tag_name="meshops-retrain-service",
    )
    return res["instance_id"]


def _sync_and_run_job(job_id: str, save_base: str, requirements_path: str, files: list, instance_id: str):
    """
    Sync retrain scripts from S3 to /opt/meshops on EC2 and execute run_retrain.sh
    """
    req_arg = f"'{requirements_path}'" if requirements_path else "''"
    files_arg = " ".join([f"'{f}'" for f in files]) if files else ""

    # -----------------------------------------------------------
    # NOTE: We export AWS_S3_BUCKET and AWS_REGION here
    # -----------------------------------------------------------
    cmd = f"""
set -eux
mkdir -p /opt/meshops
aws s3 sync s3://{S3_BUCKET}/code/meshops /opt/meshops --exact-timestamps --quiet
cd /opt/meshops
export AWS_S3_BUCKET="{S3_BUCKET}"
export AWS_REGION="{AWS_REGION}"
if [ -f requirements.txt ]; then pip3 install -r requirements.txt; fi
chmod +x run_retrain.sh
./run_retrain.sh "{job_id}" "{save_base}" {req_arg} {files_arg}
"""

    try:
        cmd_id = run_command_via_ssm(instance_id, cmd)
        jobs_store[job_id]["command_id"] = cmd_id
        jobs_store[job_id]["instance_id"] = instance_id
        jobs_store[job_id]["status"] = "RUNNING"
    except Exception as e:
        jobs_store[job_id]["status"] = "FAILED"
        jobs_store[job_id]["error"] = str(e)


# --------------------------
# API ENDPOINTS
# --------------------------
@app.post("/retrain", response_model=StatusView)
def retrain(req: RetrainRequest, background_tasks: BackgroundTasks):
    job_id = _generate_job_id(req.username, req.projectName)

    s3_report = f"s3://{S3_BUCKET}/{req.saveBase}/retrained/final_retrain_report.json"

    jobs_store[job_id] = {
        "username": req.username,
        "project": req.projectName,
        "status": "INITIALIZED",
        "s3_report": s3_report,
        "created_at": datetime.utcnow().isoformat() + "Z",
    }

    inst_id = _ensure_sandbox()
    jobs_store[job_id]["instance_id"] = inst_id

    background_tasks.add_task(
        _sync_and_run_job, job_id, req.saveBase, req.requirementsPath or "", req.files or [], inst_id
    )

    return StatusView(
        jobId=job_id,
        status="RUNNING",
        s3_report=s3_report,
        instance_id=inst_id
    )


@app.get("/status/{job_id}", response_model=StatusView)
def get_status(job_id: str):
    job = jobs_store.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail="jobId not found")

    last_console = None
    if "command_id" in job and job.get("instance_id"):
        try:
            out = get_ssm_command_output(job["instance_id"], job["command_id"])
            last_console = out
            job["last_console"] = out

            ssm_status = out.get("status")
            if ssm_status in ("Failed", "Cancelled", "TimedOut"):
                job["status"] = "FAILED"
            elif ssm_status == "Success":
                job["status"] = "COMPLETED"
            else:
                job["status"] = "RUNNING"
        except Exception as e:
            job["last_console_error"] = str(e)

    try:
        bucket, key = parse_s3_path(job["s3_report"])
        if s3_key_exists(bucket, key):
            report = s3_get_json(bucket, key)
            job["status"] = "COMPLETED"
            job["report"] = report

            return StatusView(
                jobId=job_id,
                status="COMPLETED",
                s3_report=job["s3_report"],
                report=report,
                last_console=last_console,
                instance_id=job.get("instance_id"),
                command_id=job.get("command_id"),
            )
    except Exception:
        pass

    return StatusView(
        jobId=job_id,
        status=job.get("status", "UNKNOWN"),
        s3_report=job.get("s3_report"),
        report=job.get("report"),
        last_console=job.get("last_console"),
        instance_id=job.get("instance_id"),
        command_id=job.get("command_id"),
    )


@app.get("/console/{job_id}")
def get_console(job_id: str):
    job = jobs_store.get(job_id)
    if not job or "command_id" not in job or "instance_id" not in job:
        raise HTTPException(status_code=404, detail="jobId not found or not started")

    out = get_ssm_command_output(job["instance_id"], job["command_id"])
    return {
        "jobId": job_id,
        "status": out.get("status"),
        "stdout": out.get("stdout", ""),
        "stderr": out.get("stderr", ""),
    }

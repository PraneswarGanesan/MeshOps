#!/usr/bin/env python3
import os
import uuid
from datetime import datetime
from typing import Dict, Any, List

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

load_dotenv()

app = FastAPI(title="meshops-retrain-service", version="3.0")

# --- Config from env ---
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

# In-memory job store
jobs_store: Dict[str, Dict[str, Any]] = {}

# ---------- API MODELS ----------
class RetrainRequest(BaseModel):
    username: str
    projectName: str
    files: List[str]                     # all user file paths from S3
    saveBase: str                         # base S3 prefix to save outputs
    version: str = None                   # optional, caller-managed
    requirementsPath: str = None
    extraArgs: List[str] = []              # reserved for future


class StatusView(BaseModel):
    jobId: str
    status: str
    s3_report: str = None
    report: Dict[str, Any] = None
    last_console: Dict[str, Any] = None
    instance_id: str = None
    command_id: str = None


# ---------- HELPERS ----------
def _generate_job_id(username: str, project: str) -> str:
    ts = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    uid = uuid.uuid4().hex[:6]
    return f"{username}_{project}_{ts}_{uid}"


def _ensure_sandbox() -> str:
    """
    Ensure a single EC2 instance tagged 'meshops-retrain-service' exists and running.
    """
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

    # create new if none
    res = create_instance(
        ami_id=EC2_AMI,
        instance_type=EC2_INSTANCE_TYPE,
        sg_ids=EC2_SECURITY_GROUPS,
        subnet_id=EC2_SUBNET,
        iam_profile=EC2_IAM_ROLE,
        tag_name="meshops-retrain-service",
    )
    return res["instance_id"]


def _sync_and_run_job(job_id: str, save_base: str, files: list, instance_id: str):
    """
    Sync retrain scripts to /opt/meshops and run retrain.sh
    """
    files_arg = " ".join([f"'{f}'" for f in files]) if files else ""
    cmd = f"""
set -eux
mkdir -p /opt/meshops
aws s3 sync s3://{S3_BUCKET}/code/meshops /opt/meshops --exact-timestamps --quiet
cd /opt/meshops
if [ -f requirements.txt ]; then pip3 install -r requirements.txt; fi
chmod +x run_retrain.sh
./run_retrain.sh "{job_id}" "{save_base}" {files_arg}
"""
    try:
        cmd_id = run_command_via_ssm(instance_id, cmd)
        jobs_store[job_id]["command_id"] = cmd_id
        jobs_store[job_id]["instance_id"] = instance_id
        jobs_store[job_id]["status"] = "RUNNING"
    except Exception as e:
        jobs_store[job_id]["status"] = "FAILED"
        jobs_store[job_id]["error"] = str(e)


# ---------- API ENDPOINTS ----------
@app.post("/retrain", response_model=StatusView)
def retrain(req: RetrainRequest, background_tasks: BackgroundTasks):
    job_id = _generate_job_id(req.username, req.projectName)

    # This will be where retrain.py uploads its final report
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
        _sync_and_run_job, job_id, req.saveBase, req.files or [], inst_id
    )

    return StatusView(jobId=job_id, status="RUNNING", s3_report=s3_report, instance_id=inst_id)


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

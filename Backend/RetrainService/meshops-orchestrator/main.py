#!/usr/bin/env python3
import os
import uuid
from datetime import datetime
from typing import Dict, Any, List

from fastapi import FastAPI, BackgroundTasks, HTTPException
from pydantic import BaseModel

from dotenv import load_dotenv

# Re-use your aws_utils/ec2_utils helpers (these must exist in your repo)
from aws_utils import (
    parse_s3_path,
    s3_key_exists,
    s3_get_json,
    run_command_via_ssm,
    get_ssm_command_output,
    s3_list_prefix,
)
from ec2_utils import create_instance, describe_instance

import boto3

load_dotenv()

app = FastAPI(title="meshops-orchestrator", version="2.2")

# --- Config from env ---
EC2_AMI = os.getenv("AWS_EC2_AMI")
EC2_INSTANCE_TYPE = os.getenv("AWS_EC2_INSTANCE_TYPE", "t3.small")
EC2_IAM_ROLE = os.getenv("AWS_EC2_IAM_PROFILE")
EC2_SECURITY_GROUPS = (
    os.getenv("AWS_EC2_SECURITY_GROUPS", "").split(",")
    if os.getenv("AWS_EC2_SECURITY_GROUPS")
    else []
)
EC2_SUBNET = os.getenv("AWS_EC2_SUBNET")
S3_BUCKET = os.getenv("AWS_S3_BUCKET")
AWS_REGION = os.getenv("AWS_REGION", "us-east-1")

# In-memory job store (replace with DynamoDB/RDS in prod)
jobs_store: Dict[str, Dict[str, Any]] = {}

# Models for API
class RetrainRequest(BaseModel):
    username: str
    projectName: str
    datasetPaths: List[str] = []
    s3_base_path: str = None

class StatusView(BaseModel):
    jobId: str
    status: str
    s3_report: str = None
    report: Dict[str, Any] = None
    last_console: Dict[str, Any] = None
    instance_id: str = None
    command_id: str = None

# Helpers
def _generate_job_id(username: str, project: str) -> str:
    ts = datetime.utcnow().strftime("%Y%m%dT%H%M%SZ")
    uid = uuid.uuid4().hex[:6]
    return f"{username}_{project}_{ts}_{uid}"

def _allocate_new_version(base_uri: str) -> int:
    """Scan S3 for existing versions and allocate the next vN."""
    s3 = boto3.client("s3", region_name=AWS_REGION)
    bucket, prefix = parse_s3_path(base_uri + "versions/")
    resp = s3.list_objects_v2(Bucket=bucket, Prefix=prefix, Delimiter="/")
    existing = []
    for cp in resp.get("CommonPrefixes", []):
        name = cp["Prefix"].rstrip("/").split("/")[-1]
        if name.startswith("v"):
            try:
                existing.append(int(name[1:]))
            except:
                pass
    return max(existing) + 1 if existing else 1

def _build_s3_report_path(s3_base: str, username: str, project: str, job_id: str) -> str:
    base = s3_base or f"s3://{S3_BUCKET}/{username}/{project}/artifacts/"
    if not base.endswith("/"):
        base += "/"
    vnum = _allocate_new_version(base)
    return f"{base}versions/v{vnum}/retrain_report.json"

def _ensure_sandbox() -> str:
    """Ensure single shared EC2 instance tagged meshops-shared-sandbox (create/start)."""
    ec2 = boto3.client("ec2", region_name=AWS_REGION)

    resp = ec2.describe_instances(
        Filters=[
            {"Name": "tag:Name", "Values": ["meshops-shared-sandbox"]},
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

    # create new
    res = create_instance(
        ami_id=EC2_AMI,
        instance_type=EC2_INSTANCE_TYPE,
        sg_ids=EC2_SECURITY_GROUPS,
        subnet_id=EC2_SUBNET,
        iam_profile=EC2_IAM_ROLE,
        tag_name="meshops-shared-sandbox",
    )
    return res["instance_id"]

def _sync_and_run_job(job_id: str, s3_report_path: str, dataset_paths: list, instance_id: str):
    datasets_args = " ".join([f"'{p}'" for p in dataset_paths]) if dataset_paths else ""
    cmd = f"""
set -eux
mkdir -p /opt/meshops
aws s3 sync s3://{S3_BUCKET}/code/meshops /opt/meshops --quiet || true
cd /opt/meshops
chmod +x run_retrain.sh || true
./run_retrain.sh {job_id} {s3_report_path} {datasets_args}
"""
    try:
        cmd_id = run_command_via_ssm(instance_id, cmd)
        jobs_store[job_id]["command_id"] = cmd_id
        jobs_store[job_id]["instance_id"] = instance_id
        jobs_store[job_id]["status"] = "RUNNING"
    except Exception as e:
        jobs_store[job_id]["status"] = "FAILED"
        jobs_store[job_id]["error"] = str(e)

# API endpoints
@app.post("/retrain", response_model=StatusView)
def retrain(req: RetrainRequest, background_tasks: BackgroundTasks):
    job_id = _generate_job_id(req.username, req.projectName)
    s3_report = _build_s3_report_path(req.s3_base_path, req.username, req.projectName, job_id)

    jobs_store[job_id] = {
        "username": req.username,
        "project": req.projectName,
        "status": "INITIALIZED",
        "s3_report": s3_report,
        "created_at": datetime.utcnow().isoformat() + "Z",
    }

    inst_id = _ensure_sandbox()
    jobs_store[job_id]["instance_id"] = inst_id

    background_tasks.add_task(_sync_and_run_job, job_id, s3_report, req.datasetPaths or [], inst_id)

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

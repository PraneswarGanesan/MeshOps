import boto3
import botocore
import os
import json
from typing import Tuple, Iterator


def _boto_client(service: str):
    return boto3.client(
        service,
        aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY"),
        region_name=os.getenv("AWS_REGION", "us-east-1"),
    )


def parse_s3_path(s3_path: str) -> Tuple[str, str]:
    assert s3_path.startswith("s3://"), "S3 path must start with s3://"
    no_proto = s3_path[len("s3://") :]
    bucket, key = no_proto.split("/", 1)
    return bucket, key


def s3_key_exists(bucket: str, key: str) -> bool:
    s3 = _boto_client("s3")
    try:
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except botocore.exceptions.ClientError as e:
        code = e.response.get("Error", {}).get("Code")
        if code in ("404", "NotFound"):
            return False
        raise


def s3_get_json(bucket: str, key: str):
    s3 = _boto_client("s3")
    obj = s3.get_object(Bucket=bucket, Key=key)
    body = obj["Body"].read().decode("utf-8")
    return json.loads(body)


def s3_list_prefix(bucket: str, prefix: str) -> Iterator[str]:
    s3 = _boto_client("s3")
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            yield obj["Key"]


# --- SSM Utils ---
def run_command_via_ssm(instance_id: str, command: str) -> str:
    ssm = _boto_client("ssm")
    resp = ssm.send_command(
        InstanceIds=[instance_id],
        DocumentName="AWS-RunShellScript",
        Parameters={"commands": [command]},
    )
    return resp["Command"]["CommandId"]


def get_ssm_command_output(instance_id: str, command_id: str) -> dict:
    ssm = _boto_client("ssm")
    try:
        resp = ssm.get_command_invocation(CommandId=command_id, InstanceId=instance_id)
        return {
            "status": resp["Status"],
            "stdout": resp.get("StandardOutputContent", ""),
            "stderr": resp.get("StandardErrorContent", ""),
        }
    except ssm.exceptions.InvocationDoesNotExist:
        return {"status": "PENDING", "stdout": "", "stderr": ""}

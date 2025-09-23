import os
import time
import boto3
from botocore.exceptions import ClientError

REGION = os.getenv("AWS_REGION", "us-east-1")


def _boto_session():
    return boto3.session.Session(
        aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY"),
        region_name=os.getenv("AWS_REGION", REGION),
    )


def create_instance(
    ami_id: str,
    instance_type: str,
    sg_ids: list,
    subnet_id: str,
    iam_profile: str,
    user_data: str = None,
    tag_name: str = "meshops-sandbox",
    key_name: str = None,
):
    sess = _boto_session()
    ec2 = sess.resource("ec2")

    # Default bootstrap script (installs AWS CLI + SSM agent + Python deps)
    default_user_data = """#!/bin/bash
set -eux
apt-get update -y
apt-get install -y unzip python3-pip curl snapd

# Install AWS CLI v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"
unzip /tmp/awscliv2.zip -d /tmp
/tmp/aws/install

# Ensure PATH is updated
echo 'export PATH=/usr/local/bin:$PATH' >> /home/ubuntu/.bashrc

# Install + start SSM Agent
snap install amazon-ssm-agent --classic || true
systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true

# Python deps
pip3 install boto3 pandas scikit-learn matplotlib numpy joblib
"""

    try:
        params = {
            "ImageId": ami_id,
            "MinCount": 1,
            "MaxCount": 1,
            "InstanceType": instance_type,
            "SecurityGroupIds": sg_ids,
            "SubnetId": subnet_id,
            "IamInstanceProfile": {"Name": iam_profile} if iam_profile else None,
            "UserData": user_data or default_user_data,
        }
        if key_name:
            params["KeyName"] = key_name

        instances = ec2.create_instances(**params)
        inst = instances[0]

        inst.create_tags(Tags=[{"Key": "Name", "Value": tag_name}])
        inst_id = inst.id

        inst.wait_until_running()
        inst.reload()

        public_ip = inst.public_ip_address
        timeout, waited = 120, 0
        while not public_ip and waited < timeout:
            time.sleep(2)
            waited += 2
            inst.reload()
            public_ip = inst.public_ip_address

        return {"instance_id": inst_id, "public_ip": public_ip}
    except ClientError as e:
        raise


def describe_instance(instance_id: str):
    sess = _boto_session()
    ec2 = sess.resource("ec2")
    inst = ec2.Instance(instance_id)
    inst.load()
    return {
        "id": inst.id,
        "state": inst.state,
        "public_ip": inst.public_ip_address,
        "private_ip": inst.private_ip_address,
        "instance_type": inst.instance_type,
    }

import os
import time
import boto3
from botocore.exceptions import ClientError

# default region
REGION = os.getenv("AWS_REGION", "us-east-1")


def _boto_session():
    """Create a boto3 session with env creds."""
    return boto3.session.Session(
        aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY"),
        region_name=REGION,
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
    """
    Launch or reuse an EC2 instance with 30-GB (configurable) root volume
    and required boot-strap packages.
    """
    sess = _boto_session()
    ec2 = sess.resource("ec2")

    # Use 30 GB by default unless overridden
    root_vol_size = int(os.getenv("AWS_EC2_ROOT_VOLUME_GB", "30"))

    # Default bootstrap script to prep environment
    default_user_data = f"""#!/bin/bash
set -eux
apt-get update -y
# install core packages including venv
apt-get install -y unzip python3-pip curl snapd python3-venv

# install AWS CLI v2
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip"
unzip /tmp/awscliv2.zip -d /tmp
/tmp/aws/install

# ensure PATH is updated
echo 'export PATH=/usr/local/bin:$PATH' >> /home/ubuntu/.bashrc

# install and enable SSM Agent
snap install amazon-ssm-agent --classic || true
systemctl enable snap.amazon-ssm-agent.amazon-ssm-agent.service || true
systemctl start snap.amazon-ssm-agent.amazon-ssm-agent.service || true

# install common python libs for retrain.py
pip3 install --no-cache-dir boto3 pandas scikit-learn matplotlib numpy joblib
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
            "BlockDeviceMappings": [
                {
                    "DeviceName": "/dev/sda1",
                    "Ebs": {
                        "VolumeSize": root_vol_size,   # <-- 30 GB by default
                        "VolumeType": "gp3",
                        "DeleteOnTermination": True,
                    },
                }
            ],
        }
        if key_name:
            params["KeyName"] = key_name

        # Launch instance
        instances = ec2.create_instances(**params)
        inst = instances[0]

        # Tag for later reuse
        inst.create_tags(Tags=[{"Key": "Name", "Value": tag_name}])

        inst.wait_until_running()
        inst.reload()

        # Wait for public IP
        timeout, waited = 120, 0
        public_ip = inst.public_ip_address
        while not public_ip and waited < timeout:
            time.sleep(2)
            waited += 2
            inst.reload()
            public_ip = inst.public_ip_address

        return {"instance_id": inst.id, "public_ip": public_ip}

    except ClientError as e:
        raise


def describe_instance(instance_id: str):
    """Return details of an EC2 instance."""
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

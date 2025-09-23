# deploy.py
import os, subprocess, shlex, paramiko
from typing import Tuple

def rsync_to_ec2(local_dir: str, remote_user: str, remote_host: str,
                 remote_dir: str, ssh_key_path: str,
                 port: int = 22, timeout: int = 600) -> Tuple[bool, str]:
    local_dir = os.path.abspath(local_dir)
    if not os.path.exists(local_dir):
        return False, f"local_dir {local_dir} does not exist"
    ssh_key_path = os.path.expanduser(ssh_key_path)
    cmd = (
        f'rsync -avz -e "ssh -i {shlex.quote(ssh_key_path)} '
        f'-o StrictHostKeyChecking=no -p {port}" '
        f'{shlex.quote(local_dir.rstrip("/"))}/ '
        f'{remote_user}@{remote_host}:{shlex.quote(remote_dir)}'
    )
    try:
        proc = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout, text=True)
        if proc.returncode == 0:
            return True, proc.stdout
        else:
            return False, f"rsync failed ({proc.returncode}): {proc.stderr}"
    except Exception as e:
        return False, f"rsync exception: {e}"

def sftp_upload_dir(local_dir: str, remote_user: str, remote_host: str,
                    remote_dir: str, ssh_key_path: str, port: int = 22) -> Tuple[bool, str]:
    local_dir = os.path.abspath(local_dir)
    if not os.path.exists(local_dir):
        return False, f"local_dir {local_dir} does not exist"
    ssh_key_path = os.path.expanduser(ssh_key_path)
    try:
        key = paramiko.RSAKey.from_private_key_file(ssh_key_path)
        transport = paramiko.Transport((remote_host, port))
        transport.connect(username=remote_user, pkey=key)
        sftp = paramiko.SFTPClient.from_transport(transport)

        def _mkdir_p(sftp, path):
            dirs = []
            cur = path
            while True:
                try:
                    sftp.stat(cur); break
                except IOError:
                    dirs.append(cur)
                    cur = os.path.dirname(cur)
                    if cur in ("", "/"): break
            for d in reversed(dirs):
                try: sftp.mkdir(d)
                except Exception: pass

        for root, _, files in os.walk(local_dir):
            rel = os.path.relpath(root, local_dir)
            target_root = remote_dir if rel == "." else os.path.join(remote_dir, rel).replace("\\","/")
            _mkdir_p(sftp, target_root)
            for f in files:
                local_path = os.path.join(root, f)
                remote_path = os.path.join(target_root, f).replace("\\","/")
                sftp.put(local_path, remote_path)
        sftp.close(); transport.close()
        return True, "sftp upload success"
    except Exception as e:
        return False, f"sftp exception: {e}"

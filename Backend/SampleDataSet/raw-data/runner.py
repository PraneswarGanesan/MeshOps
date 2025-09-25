#!/usr/bin/env python3
"""
runner.py - minimal Automesh runner with debug logs
 - fetches driver.py + tests.yaml from S3
 - executes driver.py with given base_dir
 - collects artifacts (csv/json/png/txt) and uploads to out_s3
 - captures driver stdout to driver_stdout.log
"""

import argparse, os, sys, subprocess, shutil, time, traceback
from pathlib import Path

def ts(): return time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime())
def log(msg): print(f"[{ts()}] {msg}", flush=True)

def run_stream_with_log(cmd, cwd=None, log_file=None) -> int:
    log(f"RUN: {' '.join(map(str, cmd))} (cwd={cwd})")
    p = subprocess.Popen(cmd, cwd=cwd,
                         stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT,
                         text=True, bufsize=1)
    assert p.stdout
    if log_file:
        with open(log_file, "w") as lf:
            for line in p.stdout:
                lf.write(line)
                lf.flush()
                print(line.rstrip(), flush=True)
    else:
        for line in p.stdout:
            print(line.rstrip(), flush=True)
    p.wait()
    return p.returncode

def fetch_driver(base_s3: str, work_dir: str):
    """Fetch driver.py from S3 into work_dir"""
    s3_path = f"{base_s3.rstrip('/')}/driver.py"
    dest = os.path.join(work_dir, "driver.py")
    log(f"DEBUG >>> Attempting to fetch driver.py from {s3_path} -> {dest}")
    rc = run_stream_with_log(["aws", "s3", "cp", s3_path, dest])
    if rc != 0:
        log(f"ERROR: failed to download driver.py from {s3_path}")
        sys.exit(6)

def fetch_extras(base_s3: str, work_dir: str):
    """Fetch additional required files like tests.yaml"""
    required = ["tests.yaml"]
    for key in required:
        s3_path = f"{base_s3.rstrip('/')}/{key}"
        dest = os.path.join(work_dir, key)
        log(f"DEBUG >>> Attempting to fetch {s3_path} -> {dest}")
        rc = run_stream_with_log(["aws", "s3", "cp", s3_path, dest])
        if rc != 0:
            log(f"WARNING: could not fetch {s3_path} (maybe missing)")

def safe_cp_local_to_s3(local_dir: str, s3_path: str) -> bool:
    log(f"DEBUG >>> Uploading {local_dir} -> {s3_path}")
    cmd = ["aws", "s3", "cp", str(local_dir), s3_path.rstrip("/") + "/", "--recursive"]
    return run_stream_with_log(cmd) == 0

# ==================== MAIN ====================

def main():
    log("DEBUG >>> runner.py main() started")
    ap = argparse.ArgumentParser()
    ap.add_argument("--base_s3", required=True)
    ap.add_argument("--out_s3", required=True)
    ap.add_argument("--task", required=False)
    ap.add_argument("--run_id", required=False)
    ap.add_argument("--no_upload", action="store_true")
    args = ap.parse_args()

    work_dir = "/tmp/project_version"
    log(f"DEBUG >>> Using work_dir={work_dir}")
    os.makedirs(work_dir, exist_ok=True)

    fetch_driver(args.base_s3, work_dir)
    driver_path = Path(work_dir) / "driver.py"

    if not driver_path.exists():
        log("FATAL: driver.py missing after fetch!")
        sys.exit(4)

    log(f"DEBUG >>> Found driver.py at {driver_path}")

    # fetch extras like tests.yaml
    fetch_extras(args.base_s3, work_dir)

    driver_log = Path(work_dir) / "driver_stdout.log"
    log("DEBUG >>> Invoking driver.py...")
    rc = run_stream_with_log(
        ["python3", str(driver_path), "--base_dir", work_dir],
        cwd=work_dir,
        log_file=driver_log
    )
    log(f"DEBUG >>> Driver exit code {rc}")

    # ✅ Collect artifacts
    if args.out_s3 and not args.no_upload:
        log(f"DEBUG >>> Checking for artifacts in {work_dir}")
        artifacts_dir = Path(work_dir) / "artifacts_out"
        artifacts_dir.mkdir(exist_ok=True)

        keep_files = [
            "tests.csv",
            "metrics.json",
            "confusion_matrix.png",
            "manifest.json",
            "refiner_hints.json",
            "logs.txt",
            "driver_stdout.log",  # captured logs
        ]

        collected = []
        for fname in keep_files:
            src = Path(work_dir) / fname
            log(f"DEBUG >>> Looking for {src}")
            if src.exists():
                try:
                    shutil.copy(src, artifacts_dir / fname)
                    collected.append(fname)
                    log(f"DEBUG >>> Collected {fname}")
                except Exception as e:
                    log(f"DEBUG >>> WARNING: could not copy {fname}: {e}")

        if collected:
            log(f"DEBUG >>> Uploading artifacts: {collected}")
        else:
            log("DEBUG >>> WARNING: No artifacts found to upload!")

        if not safe_cp_local_to_s3(str(artifacts_dir), args.out_s3):
            log("ERROR: upload failed")
            sys.exit(5)

        log("DEBUG >>> Upload complete")
    else:
        log("DEBUG >>> Skipping upload")

    sys.exit(rc)

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        tb = traceback.format_exc()
        log(f"FATAL: {e}\n{tb}")
        with open("/tmp/runner_error.log", "w") as f:
            f.write(f"[{ts()}] {tb}\n")
        sys.exit(99)

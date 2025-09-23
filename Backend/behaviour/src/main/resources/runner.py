#!/usr/bin/env python3
import os, sys, subprocess, json, boto3, traceback, shutil
from datetime import datetime

LOG_FILE = None

def log(msg):
    """Log with timestamp (stdout + logs.txt)."""
    ts = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")
    line = f"[{ts}] {msg}"
    print(line, flush=True)
    if LOG_FILE:
        try:
            with open(LOG_FILE, "a") as f:
                f.write(line + "\n")
        except Exception:
            pass

def parse_s3(uri):
    uri = uri.replace("s3://", "")
    bucket, key = uri.split("/", 1)
    return bucket, key

def upload_file(s3, local, bucket, key):
    if os.path.exists(local):
        log(f"Uploading {local} -> s3://{bucket}/{key}")
        s3.upload_file(local, bucket, key)

def upload_dir(s3, local_dir, bucket, prefix):
    for root, _, files in os.walk(local_dir):
        for f in files:
            path = os.path.join(root, f)
            rel = os.path.relpath(path, local_dir)
            dest = f"{prefix}/{rel}"
            log(f"Uploading {path} -> s3://{bucket}/{dest}")
            s3.upload_file(path, bucket, dest)

def main():
    if len(sys.argv) < 9:
        log("Usage: runner.py --base_s3 <s3://...> --out_s3 <s3://...> --task <task> --run_id <id>")
        sys.exit(1)

    args = dict(zip(sys.argv[1::2], sys.argv[2::2]))
    base_s3 = args.get("--base_s3")
    out_s3  = args.get("--out_s3")
    task    = args.get("--task")
    run_id  = args.get("--run_id")

    global LOG_FILE
    workdir = f"/tmp/run_{run_id}"
    os.makedirs(workdir, exist_ok=True)
    LOG_FILE = os.path.join(workdir,"logs.txt")

    log(f"Starting runner with run_id={run_id}, task={task}")

    bucket, base_prefix = parse_s3(base_s3)
    _, out_prefix = parse_s3(out_s3)
    s3 = boto3.client("s3")

    # 1. Download known project files
    files = ["driver.py","tests.yaml","dataset.csv","train.py","predict.py","requirements.txt"]
    for f in files:
        try:
            dest = os.path.join(workdir, f)
            s3.download_file(bucket, f"{base_prefix}/{f}", dest)
            log(f"Downloaded {f}")
        except Exception:
            log(f"Missing optional file: {f}")

    # 2. Install project requirements if present
    reqs = os.path.join(workdir, "requirements.txt")
    if os.path.exists(reqs):
        log("Installing project requirements from requirements.txt...")
        subprocess.run([sys.executable,"-m","pip","install","-r",reqs], check=False)
    else:
        log("No requirements.txt found, skipping extra installs.")

    # 3. Run driver.py (append both driver outputs + our timestamps into logs.txt)
    driver = os.path.join(workdir,"driver.py")
    try:
        log("Executing driver.py...")
        with open(LOG_FILE,"a") as logf:
            subprocess.run([sys.executable, driver, "--base_dir", workdir],
                           stdout=logf, stderr=logf, check=False)
        log("Driver finished execution.")
    except Exception:
        with open(LOG_FILE,"a") as logf:
            traceback.print_exc(file=logf)
        log("Driver execution failed — see logs.txt")

    # 4. Ensure metrics.json exists
    metrics_path = os.path.join(workdir,"metrics.json")
    if not os.path.exists(metrics_path):
        log("metrics.json not found, attempting to scrape from logs.txt")
        metrics = {}
        try:
            with open(LOG_FILE) as f:
                for line in f:
                    ls = line.strip()
                    for key in ["Accuracy","Precision","Recall","F1-score","MAE","RMSE"]:
                        if ls.startswith(key + ":"):
                            try:
                                val = float(ls.split(":")[1])
                                k = key.lower().replace("-score","")
                                metrics[k] = val
                            except: pass
            if metrics:
                with open(metrics_path,"w") as f: json.dump(metrics,f)
                log("Scraped metrics.json from logs")
        except Exception as e:
            log(f"Failed to scrape metrics: {e}")

    # 5. Ensure tests.csv exists
    tests_path = os.path.join(workdir,"tests.csv")
    if not os.path.exists(tests_path) or os.path.getsize(tests_path) == 0:
        with open(tests_path, "w") as f:
            f.write("name,category,severity,metric,threshold,expected,predicted,result\n")
        log("⚠️ Created EMPTY placeholder tests.csv")

    # 6. Upload artifacts
    for f in ["metrics.json","tests.csv","confusion_matrix.png","logs.txt","manifest.json","refiner_hints.json"]:
        local = os.path.join(workdir,f)
        try: upload_file(s3, local, bucket, f"{out_prefix}/{f}")
        except Exception: pass

    # 7. Upload heavy models
    for model in ["model.pkl","model.pt","pytorch_model.bin"]:
        local = os.path.join(workdir, model)
        if os.path.exists(local):
            upload_file(s3, local, bucket, f"{out_prefix}/{model}")

    hf_dir = os.path.join(workdir, "hf_model")
    if os.path.isdir(hf_dir):
        upload_dir(s3, hf_dir, bucket, f"{out_prefix}/hf_model")

    # 8. Upload datasets
    for sub in ["dataset.csv","images","texts","jsons"]:
        local = os.path.join(workdir, sub)
        if os.path.exists(local):
            if os.path.isdir(local):
                upload_dir(s3, local, bucket, f"{out_prefix}/{sub}")
            else:
                upload_file(s3, local, bucket, f"{out_prefix}/{sub}")

    log("Runner completed successfully.")

if __name__=="__main__":
    main()

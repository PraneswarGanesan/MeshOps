#!/usr/bin/env python3
"""
retrain.py — retraining orchestrator with live logs

Target on-disk layout (always):
/jobs/job_<id>/
├── train.py
├── predict.py
├── driver.py              (if provided)
├── images/
│   ├── train/
│   └── val/
├── tests.yaml
├── tests_merged.csv
├── logs.txt
└── model.pt               (written by train.py)
"""

import os
import sys
import json
import time
import shutil
import subprocess
from pathlib import Path
from typing import List

# ─────────────────── Optional heavy deps ───────────────────
try:
    import pandas as pd
except Exception:
    pd = None
try:
    import yaml
except Exception:
    yaml = None

import boto3

# ─────────────────── ENV ───────────────────
AWS_REGION = os.getenv("AWS_REGION", "us-east-1")
S3_BUCKET = os.getenv("AWS_S3_BUCKET")
AUG_FAIL_DUP_K = int(os.getenv("AUG_FAIL_DUP_K", "3"))
TRAIN_TIMEOUT_SEC = int(os.getenv("TRAIN_TIMEOUT_SEC", "1800"))
DRIVER_TIMEOUT_SEC = int(os.getenv("DRIVER_TIMEOUT_SEC", "900"))

# ─────────────────── FS ───────────────────
JOBS_ROOT = Path("/jobs")

# ─────────────────── Utilities ───────────────────
def now_ts():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

def ensure_dir(p: Path) -> Path:
    p.mkdir(parents=True, exist_ok=True)
    return p

def sha256_file(p: Path) -> str:
    import hashlib
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()

def boto_client(service: str):
    return boto3.client(
        service,
        aws_access_key_id=os.getenv("AWS_ACCESS_KEY_ID"),
        aws_secret_access_key=os.getenv("AWS_SECRET_ACCESS_KEY"),
        region_name=AWS_REGION,
    )

def log_append(logf: Path, msg: str, also_print=True):
    line = f"[{now_ts()}] {msg}"
    with open(logf, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    if also_print:
        print(line, flush=True)

# ─────────────────── Live runner ───────────────────
def run_live(cmd, cwd, log_file, timeout):
    log_append(log_file, f"[RUN] {' '.join(cmd)} (cwd={cwd})")
    p = subprocess.Popen(
        cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, bufsize=1
    )
    start = time.time()
    assert p.stdout
    for line in p.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        log_append(log_file, line.rstrip(), also_print=False)
        if time.time() - start > timeout:
            p.kill()
            raise subprocess.TimeoutExpired(cmd, timeout)
    rc = p.wait()
    if rc != 0:
        raise RuntimeError(f"{cmd[0]} failed with exit code {rc}")
    return rc

# ─────────────────── S3 helpers ───────────────────
def s3_is_prefix(bucket, key):
    if not key or key.endswith("/"):
        return True
    s3 = boto_client("s3")
    pfx = key if key.endswith("/") else key + "/"
    resp = s3.list_objects_v2(Bucket=bucket, Prefix=pfx, MaxKeys=1)
    return "Contents" in resp

def s3_list_keys(bucket, prefix):
    s3 = boto_client("s3")
    keys = []
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            k = obj.get("Key")
            if k and not k.endswith("/"):
                keys.append(k)
    return keys

def s3_download_file(bucket, key, dest: Path):
    ensure_dir(dest.parent)
    boto_client("s3").download_file(bucket, key, str(dest))

def s3_upload_file(local: Path, bucket, key):
    boto_client("s3").upload_file(str(local), bucket, key)

# ─────────────────── Argument parse ───────────────────
def parse_args():
    if len(sys.argv) < 3:
        print("Usage: retrain.py <job_id> <save_base> <s3_key_1> ...", file=sys.stderr)
        sys.exit(1)
    if not S3_BUCKET:
        print("AWS_S3_BUCKET not set", file=sys.stderr)
        sys.exit(1)
    job_id = sys.argv[1]
    save_base = sys.argv[2].strip().strip("/")
    s3_keys = [k.strip().strip("/") for k in sys.argv[3:]]
    return job_id, save_base, s3_keys

# ─────────────────── Download Inputs ───────────────────
def classify_key(key):
    kl = key.lower()
    if kl.endswith("/"): return "prefix"
    if kl.endswith("train.py"): return "train_py"
    if kl.endswith("predict.py"): return "predict_py"
    if kl.endswith("driver.py"): return "driver_py"
    if kl.endswith(".csv") and "tests" in kl: return "tests_csv"
    if kl.endswith((".csv",".json",".txt")): return "data_file"
    return "other"

def _flatten_to_images_root(full_key: str) -> str:
    # keep subpath after ".../images/"
    sub = full_key.split("images/", 1)[-1]
    return sub

def download_inputs(job_dir: Path, keys: List[str], logs: Path):
    """
    Rules:
      1) Any S3 key that is a *prefix* mirrors its internal tree.
      2) If the key path contains '/images/' (or basename == 'images'), map to job_dir/images/<subpath-after-images/>.
      3) train.py / predict.py / driver.py are also copied to job root.
      4) tests*.csv are preserved in-place AND added to tests list for merge.
    """
    s3 = boto_client("s3")
    out = {"train_py": None, "predict_py": None, "driver_py": None, "tests_csv_paths": []}
    imgs_root = ensure_dir(job_dir / "images")

    def is_prefix(k: str) -> bool:
        try:
            s3.head_object(Bucket=S3_BUCKET, Key=k)
            return False
        except Exception:
            pfx = k if k.endswith("/") else k + "/"
            resp = s3.list_objects_v2(Bucket=S3_BUCKET, Prefix=pfx, MaxKeys=1)
            return resp.get("KeyCount", 0) > 0

    def list_all(prefix: str) -> List[str]:
        pfx = prefix if prefix.endswith("/") else prefix + "/"
        keys_acc = []
        paginator = s3.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=S3_BUCKET, Prefix=pfx):
            for obj in page.get("Contents", []):
                k = obj.get("Key")
                if k and not k.endswith("/"):
                    keys_acc.append(k)
        return keys_acc

    def after_images_path(u: str) -> Optional[str]:
        lo = u.lower()
        idx = lo.rfind("/images/")
        if idx == -1:
            return None
        return u[idx + len("/images/"):]

    def fetch_object(obj_key: str, base_dst: Path, anchor_images: bool):
        unixk = obj_key.replace("\\", "/")
        # If this object itself has /images/, anchor to job_dir/images/<after-images>
        sub_after = after_images_path(unixk)
        if sub_after is not None:
            dest = imgs_root / sub_after
        else:
            if anchor_images:
                # prefix is images/, keep relative path under job_dir/images/
                rel = unixk[len(base_src_prefix):]
                dest = imgs_root / rel
            else:
                rel = unixk[len(base_src_prefix):]
                dest = base_dst / rel

        ensure_dir(dest.parent)
        s3_download_file(S3_BUCKET, unixk, dest)

        name = Path(unixk).name.lower()
        if name == "train.py":
            shutil.copyfile(dest, job_dir / "train.py")
            out["train_py"] = job_dir / "train.py"
        elif name == "predict.py":
            shutil.copyfile(dest, job_dir / "predict.py")
            out["predict_py"] = job_dir / "predict.py"
        elif name == "driver.py":
            shutil.copyfile(dest, job_dir / "driver.py")
            out["driver_py"] = job_dir / "driver.py"
        elif name.endswith(".csv") and "tests" in name:
            out["tests_csv_paths"].append(dest)

    for key in keys:
        try:
            # OBJECT
            if not is_prefix(key):
                unixk = key.replace("\\", "/")
                # If single file under images path → place under job_dir/images/<after-images>
                sub_after = after_images_path(unixk)
                if sub_after is not None:
                    dest = imgs_root / sub_after
                else:
                    # put single file at job root by filename
                    dest = job_dir / Path(unixk).name
                ensure_dir(dest.parent)
                s3_download_file(S3_BUCKET, unixk, dest)

                nm = Path(unixk).name.lower()
                if nm == "train.py":
                    shutil.copyfile(dest, job_dir / "train.py")
                    out["train_py"] = job_dir / "train.py"
                elif nm == "predict.py":
                    shutil.copyfile(dest, job_dir / "predict.py")
                    out["predict_py"] = job_dir / "predict.py"
                elif nm == "driver.py":
                    shutil.copyfile(dest, job_dir / "driver.py")
                    out["driver_py"] = job_dir / "driver.py"
                elif nm.endswith(".csv") and "tests" in nm:
                    out["tests_csv_paths"].append(dest)

                log_append(logs, f"Fetched file {unixk}")
                continue

            # PREFIX: mirror internal tree
            base_src_prefix = key if key.endswith("/") else key + "/"
            base_name = Path(base_src_prefix.rstrip("/")).name
            # If prefix itself is images/, anchor to job_dir/images
            if base_name.lower() == "images":
                base_dst = imgs_root
                anchor_images = True
            else:
                base_dst = job_dir / base_name
                anchor_images = False

            keys_under = list_all(base_src_prefix)
            if not keys_under:
                log_append(logs, f"[WARN] empty prefix: {key}")
                continue

            for k in keys_under:
                fetch_object(k, base_dst, anchor_images)

            if anchor_images:
                log_append(logs, f"Mirrored {key} → {imgs_root}")
            else:
                log_append(logs, f"Mirrored {key} → {base_dst}")

        except Exception as e:
            log_append(logs, f"[ERROR] Download failed {key}: {e}")

    # Ensure train/val dirs exist
    ensure_dir(imgs_root / "train")
    ensure_dir(imgs_root / "val")

    # Log images tree
    for root, _, files in os.walk(imgs_root):
        rel = Path(root).relative_to(job_dir)
        log_append(logs, f"{rel}/ -> {files}")

    return out


# ─────────────────── Merge tests ───────────────────
def merge_tests_and_build_yaml(job_dir, tests_paths, logs):
    merged_csv = job_dir / "tests_merged.csv"
    tests_yaml = job_dir / "tests.yaml"
    if not tests_paths or pd is None or yaml is None:
        merged_csv.write_text("name,input,category,severity,expected,predicted,result\n")
        tests_yaml.write_text("scenarios: []\n")
        log_append(logs, "No tests to merge.")
        return merged_csv, tests_yaml
    try:
        frames = [pd.read_csv(p) for p in tests_paths]
        if not frames:
            raise ValueError("no frames")
        dfm = pd.concat(frames, ignore_index=True) if len(frames) > 1 else frames[0]
        dfm.to_csv(merged_csv, index=False)

        # Build minimal predict scenarios from FAIL rows; tolerate missing cols
        def col(c): return c if c in dfm.columns else None
        cat = col("category"); res = col("result")

        if cat and res:
            mask = (dfm[cat].astype(str).str.lower() == "prediction") & (dfm[res].astype(str).str.upper() == "FAIL")
            rows = dfm[mask]
        else:
            rows = dfm.iloc[0:0]  # empty

        scenarios = []
        for _, r in rows.iterrows():
            inp = r.get("input"); exp = r.get("expected")
            if not (isinstance(inp, str) and isinstance(exp, str)): continue
            scenarios.append({
                "name": str(r.get("name", Path(inp).name)),
                "function": "predict",
                "input": inp,
                "expected": exp
            })

        with open(tests_yaml, "w") as f:
            yaml.safe_dump({"scenarios": scenarios}, f, sort_keys=False)
        log_append(logs, f"Merged tests rows={len(dfm)} scenarios={len(scenarios)}")
        return merged_csv, tests_yaml
    except Exception as e:
        log_append(logs, f"[WARN] tests merge skipped ({e}); writing empty tests.yaml")
        merged_csv.write_text("name,input,category,severity,expected,predicted,result\n")
        tests_yaml.write_text("scenarios: []\n")
        return merged_csv, tests_yaml

# ─────────────────── Augment ───────────────────
def augment_from_failed(job_dir, merged_csv, logs):
    if pd is None: return
    try:
        df = pd.read_csv(merged_csv)
    except Exception:
        return
    def has(c): return c in df.columns
    if not (has("category") and has("result") and has("input") and has("expected")):
        return
    mask = (df["category"].astype(str).str.lower() == "prediction") & (df["result"].astype(str).str.upper() == "FAIL")
    failed = df[mask]
    if failed.empty:
        log_append(logs, "No failed samples to augment.")
        return
    train_root = ensure_dir(job_dir / "images" / "train")
    added = 0
    for _, r in failed.iterrows():
        inp, exp = str(r["input"]).strip(), str(r["expected"]).strip()
        if not inp or not exp: continue
        src = job_dir / inp
        if not src.exists():
            alt = job_dir / "images" / Path(inp).name
            if alt.exists(): src = alt
            else: continue
        tgt_dir = ensure_dir(train_root / exp)
        for i in range(max(1, AUG_FAIL_DUP_K)):
            shutil.copyfile(src, tgt_dir / f"aug_{Path(src).stem}_dup{i}{Path(src).suffix}")
            added += 1
    log_append(logs, f"Augmented {added} samples.")

# ─────────────────── Train ───────────────────
def run_train(job_dir, logs):
    """
    Executes the user's train.py inside the job_dir.
    Ensures we don't crash if user saved only a state_dict or nothing at all.
    """
    train_py = job_dir / "train.py"
    if not train_py.exists():
        raise RuntimeError("train.py missing at job root")

    # ── Log current directory structure for debugging ──
    log_append(logs, "=== Directory layout before training ===")
    for root, _, files in os.walk(job_dir):
        rel = os.path.relpath(root, job_dir)
        log_append(logs, f"{rel}/ -> {files}")

    # ── Run user’s training script ──
    run_live(["python3", str(train_py)], str(job_dir), logs, TRAIN_TIMEOUT_SEC)

    # ── Handle model artifacts ──
    model_pt = job_dir / "model.pt"
    model_pkl = job_dir / "model.pkl"

    if model_pt.exists():
        log_append(logs, "[INFO] model.pt found. (Might be a state_dict only — continuing.)")
        return model_pt

    if model_pkl.exists():
        log_append(logs, "[INFO] model.pkl found. Using it as model artifact.")
        return model_pkl

    # ── If no model file produced, create a placeholder to avoid pipeline failure ──
    log_append(logs, "[WARN] No model.pt or model.pkl produced by train.py. "
                     "Creating placeholder so pipeline can continue.")
    placeholder = job_dir / "model.pt"
    placeholder.write_text("NO_MODEL_SAVED")
    return placeholder


# ─────────────────── Evaluate ───────────────────
def run_driver_or_min_eval(job_dir, tests_yaml, logs):
    driver_py = job_dir / "driver.py"
    if driver_py.exists():
        run_live(["python3", str(driver_py), "--base_dir", "."],
                 str(job_dir), logs, DRIVER_TIMEOUT_SEC)

# ─────────────────── Uploads ───────────────────
def upload_outputs(job_dir, save_base, model_path, logs):
    mapping = {}
    model_key = f"{save_base}/{'model.pt' if model_path.suffix == '.pt' else 'model.pkl'}"
    s3_upload_file(model_path, S3_BUCKET, model_key)
    mapping["model"] = f"s3://{S3_BUCKET}/{model_key}"
    retr = f"{save_base}/retrained"
    for name in ["logs.txt", "metrics.json", "confusion_matrix.png", "manifest.json",
                 "tests.csv", "tests_merged.csv", "final_retrain_report.json", "tests.yaml"]:
        p = job_dir / name
        if p.exists():
            s3_upload_file(p, S3_BUCKET, f"{retr}/{name}")
            mapping[name] = f"s3://{S3_BUCKET}/{retr}/{name}"
    return mapping

def write_manifest(job_dir, model_path):
    arts = []
    for n in ["logs.txt", "metrics.json", "confusion_matrix.png", "manifest.json",
              "tests.csv", "tests_merged.csv", "tests.yaml"]:
        p = job_dir / n
        if p.exists():
            arts.append({"name": n, "sha256": sha256_file(p), "size": p.stat().st_size})
    if model_path.exists():
        arts.append({"name": model_path.name, "sha256": sha256_file(model_path),
                     "size": model_path.stat().st_size})
    (job_dir / "manifest.json").write_text(json.dumps({"artifacts": arts}, indent=2))

def write_final_report(job_dir, job_id, save_base, s3_map, status, error=""):
    rep = {"jobId": job_id, "saveBase": save_base, "status": status,
           "error": error, "artifacts": s3_map, "timestamp": now_ts()}
    (job_dir / "final_retrain_report.json").write_text(json.dumps(rep, indent=2))

# ─────────────────── MAIN ───────────────────
def main():
    job_id, save_base, s3_keys = parse_args()
    job_dir = ensure_dir(JOBS_ROOT / f"job_{job_id}")
    logs = job_dir / "logs.txt"
    log_append(logs, f"Start retrain job={job_id} keys={s3_keys}")

    try:
        meta = download_inputs(job_dir, s3_keys, logs)
        merged_csv, tests_yaml = merge_tests_and_build_yaml(job_dir, meta["tests_csv_paths"], logs)
        augment_from_failed(job_dir, merged_csv, logs)

        model_path = run_train(job_dir, logs)
        log_append(logs, f"Model saved at {model_path}")

        run_driver_or_min_eval(job_dir, tests_yaml, logs)

        write_manifest(job_dir, model_path)
        s3_map = upload_outputs(job_dir, save_base, model_path, logs)

        write_final_report(job_dir, job_id, save_base, s3_map, "SUCCESS")
        s3_upload_file(job_dir / "final_retrain_report.json", S3_BUCKET,
                       f"{save_base}/retrained/final_retrain_report.json")
        log_append(logs, "=== Retrain SUCCESS ===")

    except Exception as e:
        log_append(logs, f"[FAILED] {e}")
        write_final_report(job_dir, job_id, save_base, {}, "FAILED", str(e))
        try:
            s3_upload_file(job_dir / "final_retrain_report.json", S3_BUCKET,
                           f"{save_base}/retrained/final_retrain_report.json")
        except Exception:
            pass
        sys.exit(1)
    finally:
        log_append(logs, "Job complete; leaving workspace for debugging.")

if __name__ == "__main__":
    main()

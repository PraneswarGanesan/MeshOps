#!/usr/bin/env python3
"""
retrain.py
Multi-modal retrain script (tabular / image / text / json)
- Detects dataset modality from provided dataset paths (extension or S3 prefix)
- Trains a small model:
    - tabular: sklearn (LogisticRegression/LinearRegression/RandomForest)
    - image: torchvision ResNet18 fine-tune for N classes (small epochs)
    - text: transformers DistilBERT fine-tune (small epochs)
    - json: attempts to flatten to tabular
- Writes artifacts under artifacts/versions/vN/runs/run_<job_id>/
- Writes canonical model/metrics/retrain_report at version root if promoted
- Uploads artifacts to S3 (if boto3 available)
"""
from __future__ import annotations
import argparse
import json
import os
import sys
import shutil
import tempfile
from datetime import datetime
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple

import numpy as np
import pandas as pd
import joblib
import matplotlib.pyplot as plt

# sklearn
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression, LinearRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (accuracy_score, precision_score, recall_score, f1_score,
                             mean_absolute_error, mean_squared_error, confusion_matrix)

# Optional heavy libs
HAS_TORCH = False
HAS_TRANSFORMERS = False
try:
    import torch
    import torchvision
    from torch.utils.data import Dataset, DataLoader
    from torchvision import transforms, models
    from PIL import Image
    HAS_TORCH = True
except Exception:
    HAS_TORCH = False

try:
    from transformers import AutoTokenizer, AutoModelForSequenceClassification, Trainer, TrainingArguments
    HAS_TRANSFORMERS = True
except Exception:
    HAS_TRANSFORMERS = False

# boto3 optional
try:
    import boto3
    HAS_BOTO3 = True
except Exception:
    HAS_BOTO3 = False

# ---------------------------
# Helpers: S3 utilities
# ---------------------------
def parse_s3(s3_path: str) -> Tuple[str, str]:
    assert s3_path.startswith("s3://"), "s3 path must start with s3://"
    no_proto = s3_path[len("s3://"):]
    bucket, key = no_proto.split("/", 1)
    return bucket, key

def s3_client():
    if not HAS_BOTO3:
        raise RuntimeError("boto3 not available")
    return boto3.client("s3")

def s3_download(bucket: str, key: str, local_path: str):
    s3 = s3_client()
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    s3.download_file(bucket, key, local_path)

def s3_upload(local_path: str, s3_path: str):
    bucket, key = parse_s3(s3_path)
    s3 = s3_client()
    s3.upload_file(local_path, bucket, key)

def s3_list_keys(bucket: str, prefix: str) -> List[str]:
    s3 = s3_client()
    paginator = s3.get_paginator("list_objects_v2")
    keys = []
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            keys.append(obj["Key"])
    return keys

def s3_key_exists(bucket: str, key: str) -> bool:
    try:
        s3 = s3_client()
        s3.head_object(Bucket=bucket, Key=key)
        return True
    except Exception:
        return False

def s3_get_json(bucket: str, key: str) -> Dict[str, Any]:
    s3 = s3_client()
    import io
    resp = s3.get_object(Bucket=bucket, Key=key)
    return json.load(io.BytesIO(resp["Body"].read()))

# ---------------------------
# Utilities
# ---------------------------
def now_ts() -> str:
    return datetime.utcnow().isoformat() + "Z"

def ensure_dir(p: str):
    os.makedirs(p, exist_ok=True)
    return p

def safe_read_csv(p: str) -> pd.DataFrame:
    try:
        return pd.read_csv(p)
    except Exception:
        try:
            return pd.read_csv(p, engine="python", error_bad_lines=False)
        except Exception:
            return pd.DataFrame()

def flatten_json_record(rec: Dict[str, Any]) -> Dict[str, Any]:
    out = {}
    def _flatten(obj, prefix=""):
        if isinstance(obj, dict):
            for k, v in obj.items():
                _flatten(v, f"{prefix}{k}__")
        else:
            out[prefix[:-2]] = obj
    _flatten(rec, "")
    return out

# ---------------------------
# Modality detection
# ---------------------------
def detect_modality_from_path(path: str) -> str:
    lower = path.lower()
    if lower.endswith(".csv") or lower.endswith(".tsv"):
        return "tabular"
    if lower.endswith(".json"):
        return "json"
    if lower.endswith(".txt"):
        return "text"
    if any(lower.endswith(ext) for ext in [".jpg", ".jpeg", ".png", ".bmp", ".gif"]):
        return "image_file"
    if lower.endswith("/") or lower.endswith("images") or "/images/" in lower:
        return "image_folder"
    return "tabular"

# ---------------------------
# Image training (PyTorch)
# ---------------------------
if HAS_TORCH:
    class SimpleImageDataset(Dataset):
        def __init__(self, files: List[Tuple[str,int]], transforms=None):
            self.files = files
            self.transforms = transforms
        def __len__(self):
            return len(self.files)
        def __getitem__(self, idx):
            path, label = self.files[idx]
            img = Image.open(path).convert("RGB")
            if self.transforms:
                img = self.transforms(img)
            return img, label

    def train_image_model(local_image_dir: str, work_dir: Path, logs_path: str, epochs=3, batch_size=16):
        classes = sorted([d.name for d in Path(local_image_dir).iterdir() if d.is_dir()])
        if not classes:
            raise RuntimeError("No class subfolders found under image dir")
        class_to_idx = {c:i for i,c in enumerate(classes)}
        files = []
        for c in classes:
            for f in (Path(local_image_dir)/c).glob("*"):
                if f.is_file():
                    files.append((str(f), class_to_idx[c]))
        np.random.shuffle(files)
        split = int(0.8 * len(files))
        train_files = files[:split]
        val_files = files[split:]
        ts = transforms.Compose([
            transforms.Resize((224,224)),
            transforms.ToTensor(),
            transforms.Normalize([0.485,0.456,0.406],[0.229,0.224,0.225])
        ])
        train_ds = SimpleImageDataset(train_files, transforms=ts)
        val_ds = SimpleImageDataset(val_files, transforms=ts)
        train_loader = DataLoader(train_ds, batch_size=batch_size, shuffle=True)
        val_loader = DataLoader(val_ds, batch_size=batch_size)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        model = models.resnet18(pretrained=True)
        num_ftrs = model.fc.in_features
        model.fc = torch.nn.Linear(num_ftrs, len(classes))
        model = model.to(device)
        optimizer = torch.optim.Adam(model.parameters(), lr=1e-4)
        criterion = torch.nn.CrossEntropyLoss()
        best_acc = 0.0
        for epoch in range(epochs):
            model.train()
            total = 0; correct = 0
            for xb, yb in train_loader:
                xb, yb = xb.to(device), yb.to(device)
                optimizer.zero_grad()
                out = model(xb)
                loss = criterion(out, yb)
                loss.backward(); optimizer.step()
                pred = out.argmax(dim=1)
                total += yb.size(0); correct += (pred==yb).sum().item()
            train_acc = correct/total if total else 0.0
            model.eval()
            total=0; correct=0
            y_true=[]; y_pred=[]
            with torch.no_grad():
                for xb,yb in val_loader:
                    xb,yb = xb.to(device), yb.to(device)
                    out = model(xb)
                    pred = out.argmax(dim=1)
                    total += yb.size(0); correct += (pred==yb).sum().item()
                    y_true += yb.cpu().tolist(); y_pred += pred.cpu().tolist()
            val_acc = correct/total if total else 0.0
            with open(logs_path,"a") as lf:
                lf.write(f"[{now_ts()}] Epoch {epoch+1}/{epochs}: train_acc={train_acc:.4f} val_acc={val_acc:.4f}\n")
            if val_acc > best_acc:
                best_acc = val_acc
                torch.save(model.state_dict(), str(work_dir/"model.pt"))
        metrics = {"val_accuracy": best_acc}
        try:
            cm = confusion_matrix(y_true, y_pred)
            plt.imshow(cm); plt.title("Confusion Matrix"); plt.colorbar()
            plt.savefig(work_dir/"confusion_matrix.png"); plt.close()
        except Exception:
            pass
        return model, metrics, classes

# ---------------------------
# Text training
# ---------------------------
if HAS_TRANSFORMERS and HAS_TORCH:
    class TextDataset(torch.utils.data.Dataset):
        def __init__(self, encodings, labels=None):
            self.encodings = encodings
            self.labels = labels
        def __getitem__(self, idx):
            item = {k: torch.tensor(v[idx]) for k,v in self.encodings.items()}
            if self.labels is not None:
                item["labels"] = torch.tensor(self.labels[idx])
            return item
        def __len__(self):
            return len(self.encodings["input_ids"])

    def train_text_model(texts: List[str], labels: List[int], work_dir: Path, logs_path: str, model_name="distilbert-base-uncased", epochs=3):
        tokenizer = AutoTokenizer.from_pretrained(model_name)
        enc = tokenizer(texts, truncation=True, padding=True, max_length=128)
        idxs = np.arange(len(texts))
        np.random.shuffle(idxs)
        split = int(0.8 * len(texts))
        train_idx, val_idx = idxs[:split], idxs[split:]
        train_enc = {k:[v[i] for i in train_idx] for k,v in enc.items()}
        val_enc = {k:[v[i] for i in val_idx] for k,v in enc.items()}
        train_labels = [labels[i] for i in train_idx]
        val_labels = [labels[i] for i in val_idx]
        train_ds = TextDataset(train_enc, train_labels)
        val_ds = TextDataset(val_enc, val_labels)
        num_labels = len(set(labels))
        model = AutoModelForSequenceClassification.from_pretrained(model_name, num_labels=num_labels)
        training_args = TrainingArguments(
            output_dir=str(work_dir/"tf_out"),
            num_train_epochs=epochs,
            per_device_train_batch_size=8,
            per_device_eval_batch_size=8,
            logging_steps=10,
            save_strategy="no",
            evaluation_strategy="epoch",
            disable_tqdm=True
        )
        def compute_metrics(p):
            preds = np.argmax(p.predictions, axis=1)
            acc = (preds == p.label_ids).mean()
            prec = precision_score(p.label_ids, preds, average="weighted", zero_division=0)
            rec = recall_score(p.label_ids, preds, average="weighted", zero_division=0)
            f1 = f1_score(p.label_ids, preds, average="weighted", zero_division=0)
            return {"accuracy":float(acc), "precision":float(prec), "recall":float(rec), "f1":float(f1)}
        trainer = Trainer(
            model=model,
            args=training_args,
            train_dataset=train_ds,
            eval_dataset=val_ds,
            compute_metrics=compute_metrics
        )
        trainer.train()
        metrics = trainer.evaluate()
        model.save_pretrained(str(work_dir/"hf_model"))
        tokenizer.save_pretrained(str(work_dir/"hf_model"))
        return model, metrics

# ---------------------------
# Tabular training
# ---------------------------
def train_tabular_model(df: pd.DataFrame, work_dir: Path, logs_path: str, sample_weight: Optional[np.ndarray]=None):
    def derive_target_column_local(df):
        cols = list(df.columns)
        lower = [c.lower() for c in cols]
        for cand in ("target","label","y","is_fraud","isfraud"):
            if cand in lower:
                return cols[lower.index(cand)]
        for c in reversed(cols):
            lc = c.lower()
            if not (lc.endswith("id") or lc == "index"):
                return c
        return cols[-1]
    if df.empty:
        raise RuntimeError("Empty dataframe")
    target_col = derive_target_column_local(df)
    X = df.drop(columns=[target_col], errors="ignore").select_dtypes(include=[np.number])
    y = df[target_col] if target_col in df.columns else None
    if y is None:
        raise RuntimeError("No target column found for supervised tabular training")
    X = X.fillna(X.median())
    if hasattr(y, "astype"):
        try:
            y = y.astype(float)
        except Exception:
            pass
    try:
        task = "classification" if (hasattr(y, "nunique") and y.nunique() <= 10) else "regression"
    except Exception:
        task = "classification"
    metrics = {}
    model = None
    if task == "classification":
        y_clean = y.dropna()
        X_aligned = X.loc[y_clean.index]
        X_train, X_test, y_train, y_test = train_test_split(X_aligned, y_clean, test_size=0.2, stratify=(y_clean if y_clean.nunique()>1 else None), random_state=42)
        try:
            model = LogisticRegression(max_iter=2000, random_state=42).fit(X_train, y_train, sample_weight=(None if sample_weight is None else sample_weight[:len(y_train)]))
        except Exception:
            model = RandomForestClassifier(n_estimators=100, random_state=42).fit(X_train, y_train)
        preds = model.predict(X_test)
        metrics = {
            "accuracy": float(accuracy_score(y_test,preds)),
            "precision": float(precision_score(y_test,preds,average="weighted", zero_division=0)),
            "recall": float(recall_score(y_test,preds,average="weighted", zero_division=0)),
            "f1": float(f1_score(y_test,preds,average="weighted", zero_division=0))
        }
        try:
            cm = confusion_matrix(y_test, preds)
            plt.imshow(cm); plt.title("Confusion Matrix"); plt.colorbar()
            plt.savefig(work_dir/"confusion_matrix.png"); plt.close()
        except Exception:
            pass
    else:
        X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)
        model = LinearRegression().fit(X_train, y_train)
        preds = model.predict(X_test)
        metrics = {"mae": float(mean_absolute_error(y_test,preds)), "rmse": float(mean_squared_error(y_test,preds, squared=False))}
    joblib.dump(model, str(work_dir/"model.pkl"))
    return model, metrics

# ---------------------------
# Merge failed testcases
# ---------------------------
def merge_failed_tests_into_df(df: pd.DataFrame, bucket: str, project_prefix: str, latest_v: int, work_dir: Path, logs_local: Path) -> pd.DataFrame:
    failed_rows = []
    if HAS_BOTO3 and latest_v > 0:
        try:
            for v in range(max(1, latest_v-2), latest_v+1):
                key_tests = f"{project_prefix}artifacts/versions/v{v}/tests.csv"
                if s3_key_exists(bucket, key_tests):
                    tmp = work_dir/f"tests_v{v}.csv"
                    s3_download(bucket, key_tests, str(tmp))
                    tdf = safe_read_csv(str(tmp))
                    if not tdf.empty:
                        lc = [c.lower() for c in tdf.columns]
                        if "result" in lc:
                            rc = tdf.columns[lc.index("result")]
                            frows = tdf[tdf[rc].astype(str).str.lower() != "pass"]
                            if not frows.empty:
                                failed_rows.append(frows)
            if failed_rows:
                all_failed = pd.concat(failed_rows, ignore_index=True, sort=False)
                common = [c for c in all_failed.columns if c in df.columns]
                if common:
                    df = pd.concat([df, all_failed[common]], ignore_index=True, sort=False)
                    with open(logs_local, "a") as lf:
                        lf.write(f"[{now_ts()}] Injected {len(all_failed)} failed testcase rows into dataset\n")
        except Exception as e:
            with open(logs_local, "a") as lf:
                lf.write(f"[{now_ts()}] Failed merging failed testcases: {e}\n")
    return df

# ---------------------------
# Entrypoint
# ---------------------------
def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--job_id", required=True)
    p.add_argument("--s3_report", required=True)
    p.add_argument("--datasets", nargs="+", required=True)
    p.add_argument("--s3_bucket", default=None)
    p.add_argument("--epochs", type=int, default=3)
    return p.parse_args()

def main():
    args = parse_args()
    job_id = args.job_id
    s3_report = args.s3_report
    dataset_paths = args.datasets
    epochs = args.epochs

    bucket, key = parse_s3(s3_report)
    parts = key.split("/")
    if len(parts) < 3:
        print("Expected s3 path like bucket/username/project/jobid/retrain_report.json")
        sys.exit(1)
    username, project = parts[0], parts[1]
    project_prefix = f"{username}/{project}/"

    latest_v = 0
    if HAS_BOTO3:
        try:
            keys = s3_list_keys(bucket, f"{project_prefix}artifacts/versions/")
            for k in keys:
                for p in k.split("/"):
                    if p.startswith("v") and p[1:].isdigit():
                        latest_v = max(latest_v, int(p[1:]))
        except Exception:
            latest_v = 0
    new_v = latest_v + 1
    new_v_name = f"v{new_v}"
    version_prefix = f"{project_prefix}artifacts/versions/{new_v_name}/"
    artifact_prefix = f"{version_prefix}runs/run_{job_id}/"

    work = tempfile.mkdtemp(prefix=f"retrain_{job_id}_")
    work_dir = Path(work)
    logs_local = work_dir/"logs.txt"
    with open(logs_local, "a") as lf:
        lf.write(f"[{now_ts()}] Starting retrain {job_id} for {username}/{project}\n")

    local_datasets = []
    for ds in dataset_paths:
        try:
            if ds.startswith("s3://") and HAS_BOTO3:
                b,k = parse_s3(ds)
                local_path = str(work_dir/Path(k).name)
                s3_download(b,k,local_path)
                local_datasets.append(local_path)
            else:
                if os.path.exists(ds):
                    local_datasets.append(ds)
                else:
                    with open(logs_local,"a") as lf:
                        lf.write(f"[{now_ts()}] Dataset not found: {ds}\n")
        except Exception as e:
            with open(logs_local,"a") as lf:
                lf.write(f"[{now_ts()}] Failed to fetch dataset {ds}: {e}\n")
    if not local_datasets:
        print("No datasets found; aborting")
        sys.exit(1)

    primary = local_datasets[0]
    modality = detect_modality_from_path(primary)
    model = None
    metrics = {}
    promoted = True

    try:
        if modality in ("tabular","json"):
            if modality == "json":
                try:
                    df = pd.read_json(primary, lines=True)
                except Exception:
                    with open(primary) as f:
                        raw = json.load(f)
                    if isinstance(raw, list):
                        df = pd.json_normalize(raw)
                    else:
                        df = pd.json_normalize([raw])
            else:
                df = safe_read_csv(primary)

            df = merge_failed_tests_into_df(df, bucket, project_prefix, latest_v, work_dir, logs_local)
            model, metrics = train_tabular_model(df, work_dir, str(logs_local))

        elif modality in ("image_folder","image_file"):
            if primary.startswith("s3://") and HAS_BOTO3:
                b,k = parse_s3(primary)
                objs = s3_list_keys(b, k)
                base_local = work_dir/"images"
                for obj in objs:
                    if obj.endswith("/"): continue
                    rel = obj[len(k):].lstrip("/")
                    dest = base_local/rel
                    ensure_dir(str(dest.parent))
                    s3_download(b, obj, str(dest))
                local_image_dir = str(base_local)
            else:
                if os.path.isdir(primary):
                    local_image_dir = primary
                else:
                    tmpdir = work_dir/"images"/"class_0"
                    ensure_dir(str(tmpdir))
                    shutil.copy(primary, str(tmpdir/Path(primary).name))
                    local_image_dir = str(work_dir/"images")
            if not HAS_TORCH:
                raise RuntimeError("PyTorch/torchvision not available for image training")
            model, metrics, classes = train_image_model(local_image_dir, work_dir, str(logs_local), epochs=epochs)

        elif modality == "text":
            texts=[]; labels=[]
            try:
                df = safe_read_csv(primary)
                if not df.empty and ("text" in df.columns or "sentence" in df.columns):
                    text_col = "text" if "text" in df.columns else "sentence"
                    label_col = "label" if "label" in df.columns else None
                    if label_col is None:
                        raise RuntimeError("text csv missing 'label' column")
                    texts = df[text_col].astype(str).tolist()
                    labels = df[label_col].astype(int).tolist()
                else:
                    with open(primary) as f:
                        for ln in f:
                            ln = ln.strip()
                            if not ln: continue
                            if "\t" in ln:
                                t,l = ln.split("\t",1)
                            elif "," in ln:
                                t,l = ln.rsplit(",",1)
                            else:
                                continue
                            texts.append(t)
                            labels.append(int(l))
            except Exception as e:
                raise RuntimeError(f"Failed to read text dataset: {e}")
            if not HAS_TRANSFORMERS or not HAS_TORCH:
                raise RuntimeError("transformers or torch not available for text training")
            model, metrics = train_text_model(texts, labels, work_dir, str(logs_local), epochs=epochs)

        else:
            raise RuntimeError(f"Unknown modality: {modality}")

    except Exception as e:
        with open(logs_local,"a") as lf:
            lf.write(f"[{now_ts()}] Training FAILED: {e}\n")
        report = {
            "jobId": job_id,
            "new_version": new_v_name,
            "promoted": False,
            "status": "FAILED",
            "metrics": {},
            "error": str(e),
            "timestamp": now_ts(),
            "artifact_prefix": artifact_prefix
        }
        report_local = work_dir/f"final_retrain_report_{job_id}.json"
        with open(report_local, "w") as rf:
            json.dump(report, rf, indent=2)
        try:
            if HAS_BOTO3:
                s3_upload(str(report_local), s3_report)
        except Exception:
            pass
        print("FAILED:", e)
        sys.exit(1)

    run_local_dir = work_dir/"artifacts"/"versions"/new_v_name/"runs"/f"run_{job_id}"
    ensure_dir(str(run_local_dir))
    try:
        shutil.copyfile(str(logs_local), str(run_local_dir/"logs.txt"))
    except Exception:
        pass
    try:
        if (work_dir/"model.pkl").exists():
            shutil.copyfile(str(work_dir/"model.pkl"), str(run_local_dir/"model.pkl"))
        if (work_dir/"model.pt").exists():
            shutil.copyfile(str(work_dir/"model.pt"), str(run_local_dir/"model.pt"))
        if (work_dir/"hf_model").exists():
            shutil.copytree(str(work_dir/"hf_model"), str(run_local_dir/"hf_model"), dirs_exist_ok=True)
        if (work_dir/"confusion_matrix.png").exists():
            shutil.copyfile(str(work_dir/"confusion_matrix.png"), str(run_local_dir/"confusion_matrix.png"))
    except Exception:
        pass
    try:
        metrics_local = run_local_dir/"metrics.json"
        with open(metrics_local,"w") as mf:
            json.dump(metrics, mf, indent=2)
    except Exception:
        pass

    report = {
        "jobId": job_id,
        "new_version": new_v_name,
        "promoted": promoted,
        "status": "SUCCESS",
        "metrics": metrics,
        "timestamp": now_ts(),
        "artifact_prefix": artifact_prefix,
    }
    report_local = run_local_dir/"retrain_report.json"
    with open(report_local,"w") as rf:
        json.dump(report, rf, indent=2)

    if HAS_BOTO3:
        for root, _, files in os.walk(run_local_dir):
            for f in files:
                local_f = os.path.join(root,f)
                rel = os.path.relpath(local_f, run_local_dir)
                s3_path = f"s3://{bucket}/{version_prefix}runs/run_{job_id}/{rel}"
                try:
                    s3_upload(local_f, s3_path)
                except Exception as e:
                    with open(logs_local,"a") as lf:
                        lf.write(f"[{now_ts()}] Failed upload {local_f} -> {s3_path}: {e}\n")
        try:
            s3_upload(str(report_local), f"s3://{bucket}/{version_prefix}retrain_report.json")
            s3_upload(str(metrics_local), f"s3://{bucket}/{version_prefix}metrics.json")
            if (run_local_dir/"model.pkl").exists():
                s3_upload(str(run_local_dir/"model.pkl"), f"s3://{bucket}/{version_prefix}model.pkl")
            if (run_local_dir/"model.pt").exists():
                s3_upload(str(run_local_dir/"model.pt"), f"s3://{bucket}/{version_prefix}model.pt")
            if (run_local_dir/"hf_model").exists():
                for root, _, files in os.walk(run_local_dir/"hf_model"):
                    for f in files:
                        local_f = os.path.join(root,f)
                        rel = os.path.relpath(local_f, run_local_dir/"hf_model")
                        s3_upload(local_f, f"s3://{bucket}/{version_prefix}hf_model/{rel}")
        except Exception as e:
            with open(logs_local,"a") as lf:
                lf.write(f"[{now_ts()}] Warning uploading canonical artifacts: {e}\n")

    final_report = run_local_dir/"final_retrain_report.json"
    with open(final_report, "w") as f:
        json.dump(report, f, indent=2)
    try:
        if HAS_BOTO3:
            s3_upload(str(final_report), s3_report)
    except Exception:
        pass

    print(f"[AAAR+] Retrain finished. Version {new_v_name} created. promoted={promoted}")
    with open(logs_local,"a") as lf:
        lf.write(f"[{now_ts()}] Retrain finished. Version {new_v_name} created. promoted={promoted}\n")
    sys.exit(0)

if __name__ == "__main__":
    main()

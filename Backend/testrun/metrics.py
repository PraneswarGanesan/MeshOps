import os
import time
import json
from typing import Dict, Optional

# Handle optional dependencies gracefully
try:
    import numpy as np
except ImportError:
    print("[WARN] numpy not available, using fallback implementations")
    np = None

try:
    from scipy.stats import wasserstein_distance
except ImportError:
    print("[WARN] scipy not available, using fallback wasserstein distance")
    def wasserstein_distance(u_values, v_values):
        """Fallback implementation of wasserstein distance"""
        try:
            if np is not None:
                u_values = np.asarray(u_values)
                v_values = np.asarray(v_values)
                return float(np.abs(np.mean(u_values) - np.mean(v_values)))
            else:
                u_mean = sum(u_values) / len(u_values) if u_values else 0.0
                v_mean = sum(v_values) / len(v_values) if v_values else 0.0
                return abs(u_mean - v_mean)
        except Exception:
            return 0.0

# ---------- Reference stats IO ----------

def load_reference_stats(path: str) -> Dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

def save_reference_stats(path: str, stats: Dict) -> None:
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2)

def compute_reference_stats_from_csv(csv_path: str, max_rows: int = 200_000) -> Dict:
    """Build simple numeric reference: per-column mean/std and quantiles."""
    if np is None:
        print("[WARN] numpy not available, returning empty stats")
        return {"created_ts": time.time(), "columns": {}}
    
    try:
        arr = np.genfromtxt(csv_path, delimiter=",", names=True, dtype=None, encoding=None)
        stats = {}
        for name in arr.dtype.names:
            try:
                col = np.asarray(arr[name], dtype=float)
                col = col[np.isfinite(col)]
                if col.size == 0:
                    continue
                n = min(col.size, max_rows)
                c = col[:n]
                stats[name] = {
                    "mean": float(np.mean(c)),
                    "std": float(np.std(c) + 1e-12),
                    "q": list(np.quantile(c, [0.05, 0.25, 0.5, 0.75, 0.95]))
                }
            except Exception:
                continue
        return {"created_ts": time.time(), "columns": stats}
    except Exception as e:
        print(f"[WARN] Failed to compute reference stats: {e}")
        return {"created_ts": time.time(), "columns": {}}

def load_csv_numeric_columns(csv_path: str) -> Dict:
    """Load numeric columns from CSV, with fallback for missing numpy"""
    if np is None:
        print("[WARN] numpy not available, returning empty columns")
        return {}
    
    try:
        arr = np.genfromtxt(csv_path, delimiter=",", names=True, dtype=None, encoding=None)
        out = {}
        for name in arr.dtype.names:
            try:
                col = np.asarray(arr[name], dtype=float)
                col = col[np.isfinite(col)]
                if col.size:
                    out[name] = col
            except Exception:
                continue
        return out
    except Exception as e:
        print(f"[WARN] Failed to load CSV columns: {e}")
        return {}

# ---------- Metrics ----------

def drift_wasserstein(ref_stats: Dict, new_cols: Dict) -> float:
    """Mean 1D Wasserstein distance across numeric columns. Capped to 10."""
    if np is None:
        return 0.0
    
    try:
        cols = ref_stats.get("columns", {})
        scores = []
        for name, col in new_cols.items():
            if name not in cols:
                continue
            mu = float(cols[name]["mean"])
            sd = float(cols[name]["std"])
            if sd <= 0.0:
                continue
            # sample synthetic reference to compare with observed new column
            k = int(min(col.size, 5000))
            if k <= 1:
                continue
            ref_sample = np.random.normal(mu, sd, size=k)
            new_sample = col[:k]
            scores.append(wasserstein_distance(ref_sample, new_sample))
        if not scores:
            return 0.0
        return float(np.clip(np.mean(scores), 0.0, 10.0))
    except Exception as e:
        print(f"[WARN] Drift calculation failed: {e}")
        return 0.0

def entropy_from_probs(probs) -> float:
    """Normalized predictive entropy in [0,1]. probs shape: (n, C)."""
    if np is None:
        return 0.0
    
    try:
        if not hasattr(probs, 'ndim') or probs.ndim != 2 or probs.shape[1] < 2:
            return 0.0
        eps = 1e-12
        H = -np.sum(probs * np.log(probs + eps), axis=1)
        Hn = float(np.mean(H) / np.log(probs.shape[1]))
        return float(np.clip(Hn, 0.0, 1.0))
    except Exception as e:
        print(f"[WARN] Entropy calculation failed: {e}")
        return 0.0

def estimate_cost_minutes(dataset_mb: float, base_mb_per_min: float = 200.0) -> float:
    """Simple throughput model: minutes = MB / rate."""
    r = max(base_mb_per_min, 1e-3)
    return float(max(dataset_mb / r, 0.1))

def file_size_mb(path: str) -> float:
    try:
        return os.path.getsize(path) / (1024 * 1024)
    except Exception:
        return 0.0

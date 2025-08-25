# train.py
import os
import glob
import json
from typing import List, Tuple, Optional, Dict

import numpy as np
from PIL import Image
from sklearn.cluster import KMeans
from sklearn.preprocessing import StandardScaler
from sklearn.pipeline import Pipeline
import joblib


# ------------------ config / paths ------------------

IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".gif", ".webp"}

def _images_root(base_dir: str) -> str:
    """
    Read images from <base_dir>/raw-data if present, else <base_dir>.
    """
    rd = os.path.join(base_dir, "raw-data")
    return rd if os.path.isdir(rd) else base_dir


# ------------------ dataset & features ------------------

def _is_image(path: str) -> bool:
    return os.path.splitext(path)[1].lower() in IMAGE_EXTS

def _label_from_name(name: str) -> str:
    """
    Heuristic ground-truth from filename for evaluation.
    """
    n = name.lower()
    if "penguin" in n:
        return "penguin"
    if "cat" in n:
        return "cat"
    if "dog" in n:
        return "dog"
    return "other"

def _list_images(img_root: str) -> List[str]:
    files: List[str] = []
    for ext in IMAGE_EXTS:
        files.extend(glob.glob(os.path.join(img_root, f"**/*{ext}"), recursive=True))
    files = [p for p in sorted(set(files)) if os.path.isfile(p)]
    return files

def _extract_feature(path: str, size: int = 128, bins: int = 8) -> np.ndarray:
    """
    Simple, deterministic features:
      • resize → (size x size)
      • 8-bin per-channel histogram (R/G/B) → 24 dims, L1-normalized
    """
    img = Image.open(path).convert("RGB").resize((size, size))
    arr = np.array(img)
    feats = []
    for ch in range(3):
        hist, _ = np.histogram(arr[..., ch], bins=bins, range=(0, 255), density=False)
        feats.append(hist.astype(np.float32))
    v = np.concatenate(feats, axis=0)  # (24,)
    s = v.sum()
    if s > 0:
        v /= s
    return v

def load_dataset(base_dir: str) -> Tuple[np.ndarray, List[str], List[str]]:
    """
    Returns:
      X : (N, 24) float32 features
      y : list[str] labels inferred from filenames (for evaluation)
      files : list[str] file paths
    """
    root = _images_root(base_dir)
    files = _list_images(root)
    if not files:
        raise RuntimeError(f"No images found under: {root}")
    X = np.stack([_extract_feature(p) for p in files], axis=0).astype(np.float32)
    y = [_label_from_name(os.path.basename(p)) for p in files]
    return X, y, files


# ------------------ K-Means training ------------------

def _make_pipeline(n_clusters: int) -> Pipeline:
    """
    Standardize histograms then K-Means cluster.
    """
    return Pipeline([
        ("scaler", StandardScaler(with_mean=True, with_std=True)),
        ("kmeans", KMeans(n_clusters=n_clusters, n_init=10, random_state=42)),
    ])

def _majority_mapping(true_labels: List[str], cluster_ids: np.ndarray) -> Dict[int, str]:
    """
    Map each cluster id -> majority true label observed in that cluster.
    """
    from collections import Counter, defaultdict
    buckets = defaultdict(list)
    for t, c in zip(true_labels, cluster_ids.tolist()):
        buckets[c].append(t)
    mapping: Dict[int, str] = {}
    for c, lab_list in buckets.items():
        counts = Counter(lab_list)
        majority_label = counts.most_common(1)[0][0]
        mapping[c] = majority_label
    return mapping

def _ordered_class_names(true_labels: List[str]) -> List[str]:
    """
    Stable order for metrics/indices: sorted unique labels.
    """
    return sorted(list({t for t in true_labels}))

def _labels_to_indices(labels: List[str], order: List[str]) -> np.ndarray:
    idx = {c: i for i, c in enumerate(order)}
    return np.array([idx.get(t, -1) for t in labels], dtype=int)


def train_model(base_dir: str,
                out_model_path: Optional[str] = None,
                out_info_path: Optional[str] = None) -> Tuple[str, str]:
    """
    Train K-Means on ALL images and persist:
      • model.pkl (sklearn Pipeline with KMeans)
      • clustering.json (class_names + cluster_to_label mapping)
    Returns (model_path, info_path).
    """
    X, y, _ = load_dataset(base_dir)
    # number of clusters = number of unique true labels observed
    class_names = _ordered_class_names(y)
    n_clusters = max(2, len(class_names))  # at least 2 to force splits

    pipe = _make_pipeline(n_clusters=n_clusters)
    pipe.fit(X)
    cluster_ids = pipe["kmeans"].labels_

    cluster_to_label = _majority_mapping(y, cluster_ids)

    model_path = out_model_path or os.path.join(base_dir, "model.pkl")
    info_path = out_info_path or os.path.join(base_dir, "clustering.json")

    joblib.dump(pipe, model_path)
    with open(info_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "class_names": class_names,           # order used for indices
                "cluster_to_label": {int(k): v for k, v in cluster_to_label.items()},
            },
            f,
            indent=2
        )

    return model_path, info_path


def train_and_predict(base_dir: str):
    """
    Fit K-Means, then predict clusters for ALL images.
    Convert clusters → labels via majority mapping.
    Return (y_true_idx, y_pred_idx, class_names)
    """
    X, y, _ = load_dataset(base_dir)
    class_names = _ordered_class_names(y)
    n_clusters = max(2, len(class_names))

    pipe = _make_pipeline(n_clusters=n_clusters)
    pipe.fit(X)
    clusters = pipe["kmeans"].labels_

    # majority mapping from training split (same data here)
    cluster_to_label = _majority_mapping(y, clusters)

    # save artifacts so driver can report & later inference can reuse
    model_path = os.path.join(base_dir, "model.pkl")
    info_path = os.path.join(base_dir, "clustering.json")
    joblib.dump(pipe, model_path)
    with open(info_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "class_names": class_names,
                "cluster_to_label": {int(k): v for k, v in cluster_to_label.items()},
            },
            f,
            indent=2
        )

    # Build y_pred labels from clusters
    y_pred_labels = [cluster_to_label.get(int(c), "other") for c in clusters]

    # Map both to indices using class_names order
    y_true_idx = _labels_to_indices(y, class_names)
    y_pred_idx = _labels_to_indices(y_pred_labels, class_names)

    return y_true_idx, y_pred_idx, class_names


# ------------------ CLI (optional) ------------------

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_dir", required=True)
    args = parser.parse_args()

    yt, yp, labels = train_and_predict(args.base_dir)
    print("Labels:", labels)
    print("y_true:", yt.tolist())
    print("y_pred:", yp.tolist())
    print("Model saved to:", os.path.join(args.base_dir, "model.pkl"))

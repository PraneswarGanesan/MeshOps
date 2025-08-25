# predict.py
import os
import json
from typing import Tuple, List, Optional

import numpy as np
import joblib
from train import load_dataset, _extract_feature  # reuse


def _load_info(base_dir: str):
    path = os.path.join(base_dir, "clustering.json")
    if not os.path.isfile(path):
        # fallback for older runs — fabricate minimal info
        return {"class_names": [], "cluster_to_label": {}}
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)

def _labels_to_indices(labels: List[str], order: List[str]) -> np.ndarray:
    idx = {c: i for i, c in enumerate(order)}
    return np.array([idx.get(t, -1) for t in labels], dtype=int)


def predict_folder(base_dir: str,
                   model=None,
                   model_path: Optional[str] = None) -> Tuple[np.ndarray, np.ndarray, List[str]]:
    """
    Predict clusters for every image, then map to class labels using
    the saved majority mapping from training.
    Returns (y_true_idx, y_pred_idx, class_names)
    """
    X, y, _ = load_dataset(base_dir)

    # load model if not provided
    if model is None:
        mp = model_path or os.path.join(base_dir, "model.pkl")
        if not os.path.isfile(mp):
            raise FileNotFoundError(f"Model not found: {mp}")
        model = joblib.load(mp)

    info = _load_info(base_dir)
    class_names: List[str] = info.get("class_names") or sorted(list(set(y)))
    cluster_to_label = {int(k): v for k, v in (info.get("cluster_to_label") or {}).items()}

    # predict cluster ids, then map to labels
    try:
        clusters = model.predict(X)
    except Exception:
        # Some sklearn versions need transform + argmin to emulate predict for KMeans in pipelines
        kmeans = getattr(model, "named_steps", {}).get("kmeans", None)
        Xtrans = model[:-1].transform(X) if hasattr(model, "__getitem__") else X  # pipeline preprocessing
        if kmeans is None:
            raise
        # distance to centers, choose nearest
        centers = kmeans.cluster_centers_
        # If StandardScaler present, inputs already scaled by pipeline[:-1]
        d2 = ((Xtrans[:, None, :] - centers[None, :, :]) ** 2).sum(axis=2)
        clusters = np.argmin(d2, axis=1)

    y_pred_labels = [cluster_to_label.get(int(c), "other") for c in clusters]

    y_true_idx = _labels_to_indices(y, class_names)
    y_pred_idx = _labels_to_indices(y_pred_labels, class_names)

    return y_true_idx, y_pred_idx, class_names


# -------------- CLI (optional) --------------

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--base_dir", required=True)
    parser.add_argument("--model_path", default=None)
    args = parser.parse_args()

    y_true, y_pred, labels = predict_folder(args.base_dir, model_path=args.model_path)
    print("Labels:", labels)
    print("y_true:", y_true.tolist())
    print("y_pred:", y_pred.tolist())

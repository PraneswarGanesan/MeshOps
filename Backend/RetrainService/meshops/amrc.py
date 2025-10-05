import os
import time
from dataclasses import dataclass
from typing import Optional, Dict
import numpy as np

from .state_store import StateStore
from .metrics import (
    load_reference_stats,
    load_csv_numeric_columns,
    drift_wasserstein,
    entropy_from_probs,
    estimate_cost_minutes,
    file_size_mb,
)

@dataclass
class AMRCConfig:
    alpha: float = 1.0      # drift weight scaler
    beta: float = 1.0       # entropy weight scaler
    gamma: float = 1.0      # cost weight scaler
    delta: float = 1.0      # fatigue weight scaler
    lr: float = 0.05        # adaptation step
    min_gap_minutes: float = 30.0
    cost_per_min: float = 0.0  # set >0 to convert minutes->currency proxy

class AMRC:
    """
    Adaptive Multi-Signal Retrain Controller.
    Decision score: R = w·[α*drift, β*entropy, -γ*cost, -δ*fatigue]
    Retrain if R > θ. After each cycle, adapt w and θ using outcome feedback.
    """
    def __init__(self, state_path: str, cfg: AMRCConfig):
        self.state = StateStore(state_path)
        self.cfg = cfg

    def _fatigue(self, last_ts: float) -> float:
        if last_ts <= 0:
            return 0.0
        minutes = (time.time() - last_ts) / 60.0
        f = max(0.0, (self.cfg.min_gap_minutes - minutes)) / self.cfg.min_gap_minutes
        return float(np.clip(f, 0.0, 1.0))

    def decide(
        self,
        ref_stats_path: str,
        new_csv_path: str,
        probs_csv_path: Optional[str] = None,
    ) -> Dict:
        st = self.state.get()
        w = np.array(st["w"], dtype=float)  # [w1,w2,w3,w4]
        theta = float(st["theta"])
        last_ts = float(st["last_retrain_ts"])

        # signals
        ref_stats = load_reference_stats(ref_stats_path)
        new_cols = load_csv_numeric_columns(new_csv_path)
        s1_raw = drift_wasserstein(ref_stats, new_cols)      # 0..~10
        s1 = float(np.clip(s1_raw / 5.0, 0.0, 1.0))          # normalize to 0..1

        s2 = 0.0
        if probs_csv_path and os.path.exists(probs_csv_path):
            probs = np.genfromtxt(probs_csv_path, delimiter=",")
            if probs.ndim == 1:
                probs = probs.reshape(-1, 2)
            s2 = entropy_from_probs(probs)                   # 0..1

        mb = file_size_mb(new_csv_path)
        cost_min = estimate_cost_minutes(mb)
        s3 = cost_min if self.cfg.cost_per_min <= 0 else cost_min * self.cfg.cost_per_min
        s3 = float(np.clip(s3 / 30.0, 0.0, 1.0))             # 30 min → 1.0 norm

        s4 = self._fatigue(last_ts)                          # 0..1

        signals = np.array([
            self.cfg.alpha * s1,
            self.cfg.beta  * s2,
           -self.cfg.gamma * s3,
           -self.cfg.delta * s4
        ], dtype=float)

        R = float(np.dot(w, signals))
        retrain = R > theta

        return {
            "signals": {"drift": s1, "entropy": s2, "cost": s3, "fatigue": s4},
            "raw": {"drift_wasserstein": s1_raw, "data_mb": mb, "cost_min": cost_min},
            "weights": {"w1": float(w[0]), "w2": float(w[1]), "w3": float(w[2]), "w4": float(w[3])},
            "theta": theta,
            "score": R,
            "retrain": bool(retrain)
        }

    def adapt(self, outcome_error: float, outcome_cost_minutes: float) -> None:
        """Online update. Increase drift/entropy weights when error is high.
        Increase cost/fatigue weights when cost is high. Adjust theta for stability."""
        st = self.state.get()
        w = np.array(st["w"], dtype=float)
        theta = float(st["theta"])

        err = float(np.clip(outcome_error, 0.0, 1.0))
        cst = float(np.clip(outcome_cost_minutes / 30.0, 0.0, 1.0))

        # push sensitivity to error and cost
        w[0] = float(np.clip(w[0] + self.cfg.lr * err, 0.0, 2.0))  # drift
        w[1] = float(np.clip(w[1] + self.cfg.lr * err, 0.0, 2.0))  # entropy
        w[2] = float(np.clip(w[2] + self.cfg.lr * cst, 0.0, 2.0))  # cost
        w[3] = float(np.clip(w[3] + self.cfg.lr * cst, 0.0, 2.0))  # fatigue

        # keep firing rate reasonable by nudging theta against combined pressure
        theta = float(np.clip(theta + self.cfg.lr * (0.5 - 0.5*err - 0.5*cst), 0.1, 1.5))

        self.state.update(w=list(map(float, w)), theta=theta)

    def mark_retrained(self) -> None:
        self.state.mark_retrain_now()

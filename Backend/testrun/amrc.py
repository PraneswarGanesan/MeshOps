import os
import time
from dataclasses import dataclass
from typing import Optional, Dict

# ─────────────────────────────────────────────
# Optional dependencies
# ─────────────────────────────────────────────
try:
    import numpy as np
except ImportError:
    print("[WARN] numpy not available, using fallback implementations")
    np = None

# ─────────────────────────────────────────────
# Local imports (NO leading dot)
# ─────────────────────────────────────────────
try:
    from state_store import StateStore
except ImportError:
    print("[ERROR] state_store module not found")
    StateStore = None

try:
    from metrics import (
        load_reference_stats,
        load_csv_numeric_columns,
        drift_wasserstein,
        entropy_from_probs,
        estimate_cost_minutes,
        file_size_mb,
    )
except ImportError:
    print("[WARN] metrics module not available, using fallback implementations")

    # ────── Fallbacks ──────
    def load_reference_stats(path: str) -> Dict:
        try:
            import json
            with open(path, 'r') as f:
                return json.load(f)
        except Exception:
            return {"columns": {}}

    def load_csv_numeric_columns(path: str) -> Dict:
        return {}

    def drift_wasserstein(ref_stats: Dict, new_cols: Dict) -> float:
        return 0.0

    def entropy_from_probs(probs) -> float:
        return 0.0

    def estimate_cost_minutes(mb: float) -> float:
        return max(mb / 200.0, 0.1)

    def file_size_mb(path: str) -> float:
        try:
            return os.path.getsize(path) / (1024 * 1024)
        except Exception:
            return 0.0


# ─────────────────────────────────────────────
# Config dataclass
# ─────────────────────────────────────────────
@dataclass
class AMRCConfig:
    alpha: float = 1.0      # drift weight scaler
    beta: float = 1.0       # entropy weight scaler
    gamma: float = 1.0      # cost weight scaler
    delta: float = 1.0      # fatigue weight scaler
    lr: float = 0.05        # adaptation step
    min_gap_minutes: float = 30.0
    cost_per_min: float = 0.0  # convert minutes → currency proxy if needed


# ─────────────────────────────────────────────
# AMRC Main Class
# ─────────────────────────────────────────────
class AMRC:
    """
    Adaptive Multi-Signal Retrain Controller.
    Decision score: R = w·[α*drift, β*entropy, -γ*cost, -δ*fatigue]
    Retrain if R > θ. After each cycle, adapt w and θ using outcome feedback.
    """

    def __init__(self, state_path: str, cfg: AMRCConfig):
        self.cfg = cfg
        try:
            if StateStore is not None:
                self.state = StateStore(state_path)
            else:
                print("[WARN] StateStore not available, AMRC will use fallback mode")
                self.state = None
        except Exception as e:
            print(f"[ERROR] Failed to initialize StateStore: {e}")
            self.state = None

    # ─────────────────────────────────────────────
    def _fatigue(self, last_ts: float) -> float:
        if last_ts <= 0:
            return 0.0
        minutes = (time.time() - last_ts) / 60.0
        f = max(0.0, (self.cfg.min_gap_minutes - minutes)) / self.cfg.min_gap_minutes
        return float(np.clip(f, 0.0, 1.0)) if np is not None else float(max(0.0, min(f, 1.0)))

    # ─────────────────────────────────────────────
    def decide(
        self,
        ref_stats_path: str,
        new_csv_path: str,
        probs_csv_path: Optional[str] = None,
    ) -> Dict:
        if self.state is None:
            return self._fallback_decision()

        try:
            st = self.state.get()
            w = np.array(st["w"], dtype=float) if np is not None else st["w"]
            theta = float(st["theta"])
            last_ts = float(st["last_retrain_ts"])

            # ----- signals -----
            ref_stats = load_reference_stats(ref_stats_path) if os.path.exists(ref_stats_path) else {"columns": {}}
            new_cols = load_csv_numeric_columns(new_csv_path) if os.path.exists(new_csv_path) else {}

            s1_raw = drift_wasserstein(ref_stats, new_cols)      # 0..~10
            s1 = float(np.clip(s1_raw / 5.0, 0.0, 1.0)) if np is not None else float(max(0.0, min(s1_raw / 5.0, 1.0)))

            # entropy
            s2 = 0.0
            if probs_csv_path and os.path.exists(probs_csv_path):
                try:
                    if np is not None:
                        probs = np.genfromtxt(probs_csv_path, delimiter=",")
                        if probs.ndim == 1:
                            probs = probs.reshape(-1, 2)
                        s2 = entropy_from_probs(probs)
                except Exception:
                    s2 = 0.0

            # cost
            mb = file_size_mb(new_csv_path)
            cost_min = estimate_cost_minutes(mb)
            s3 = cost_min if self.cfg.cost_per_min <= 0 else cost_min * self.cfg.cost_per_min
            s3 = float(np.clip(s3 / 30.0, 0.0, 1.0)) if np is not None else float(max(0.0, min(s3 / 30.0, 1.0)))

            # fatigue
            s4 = self._fatigue(last_ts)

            # decision
            if np is not None:
                signals = np.array([
                    self.cfg.alpha * s1,
                    self.cfg.beta * s2,
                    -self.cfg.gamma * s3,
                    -self.cfg.delta * s4
                ], dtype=float)
                R = float(np.dot(w, signals))
            else:
                sig = [self.cfg.alpha * s1, self.cfg.beta * s2, -self.cfg.gamma * s3, -self.cfg.delta * s4]
                R = float(sum(w[i] * sig[i] for i in range(len(w))))

            retrain = R > theta

            return {
                "signals": {"drift": s1, "entropy": s2, "cost": s3, "fatigue": s4},
                "raw": {"drift_wasserstein": s1_raw, "data_mb": mb, "cost_min": cost_min},
                "weights": {"w1": float(w[0]), "w2": float(w[1]), "w3": float(w[2]), "w4": float(w[3])},
                "theta": theta,
                "score": R,
                "retrain": bool(retrain)
            }
        except Exception as e:
            print(f"[ERROR] AMRC decision failed: {e}")
            return self._fallback_decision()

    # ─────────────────────────────────────────────
    def _fallback_decision(self) -> Dict:
        """Fallback decision when dependencies are missing"""
        return {
            "signals": {"drift": 0.0, "entropy": 0.0, "cost": 0.0, "fatigue": 0.0},
            "raw": {"drift_wasserstein": 0.0, "data_mb": 0.0, "cost_min": 0.0},
            "weights": {"w1": 0.4, "w2": 0.3, "w3": 0.2, "w4": 0.1},
            "theta": 0.5,
            "score": 0.0,
            "retrain": False
        }

    # ─────────────────────────────────────────────
    def adapt(self, outcome_error: float, outcome_cost_minutes: float) -> None:
        """Online update of weights and threshold based on outcome feedback"""
        if self.state is None:
            print("[WARN] Cannot adapt - state store not available")
            return

        try:
            st = self.state.get()
            w = np.array(st["w"], dtype=float) if np is not None else st["w"]

            err = float(np.clip(outcome_error, 0.0, 1.0)) if np is not None else float(max(0.0, min(outcome_error, 1.0)))
            cst = float(np.clip(outcome_cost_minutes / 30.0, 0.0, 1.0)) if np is not None else float(max(0.0, min(outcome_cost_minutes / 30.0, 1.0)))

            # adjust weights
            w[0] = float(np.clip(w[0] + self.cfg.lr * err, 0.0, 2.0))  # drift
            w[1] = float(np.clip(w[1] + self.cfg.lr * err, 0.0, 2.0))  # entropy
            w[2] = float(np.clip(w[2] + self.cfg.lr * cst, 0.0, 2.0))  # cost
            w[3] = float(np.clip(w[3] + self.cfg.lr * cst, 0.0, 2.0))  # fatigue

            theta = float(st["theta"])
            theta = float(np.clip(theta + self.cfg.lr * (0.5 - 0.5 * err - 0.5 * cst), 0.1, 1.5)) if np is not None else float(max(0.1, min(theta + self.cfg.lr * (0.5 - 0.5 * err - 0.5 * cst), 1.5)))

            self.state.update(w=list(map(float, w)), theta=theta)
        except Exception as e:
            print(f"[ERROR] AMRC adaptation failed: {e}")

    # ─────────────────────────────────────────────
    def mark_retrained(self) -> None:
        if self.state is not None:
            try:
                self.state.mark_retrain_now()
            except Exception as e:
                print(f"[ERROR] Failed to mark retrained: {e}")
        else:
            print("[WARN] Cannot mark retrained - state store not available")

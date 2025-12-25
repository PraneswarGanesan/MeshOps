# simulate_amrc.py
import os, csv, time
import numpy as np
import matplotlib.pyplot as plt

from amrc import AMRC, AMRCConfig
from metrics import compute_reference_stats_from_csv, save_reference_stats
from state_store import StateStore

# ----------------------------------------------------------
# 0) Global config
# ----------------------------------------------------------
os.makedirs("sim_data", exist_ok=True)
ref_csv = "sim_data/ref.csv"
ref_stats_json = "sim_data/ref_stats.json"
state_path = "sim_data/amrc_state.json"

np.random.seed(42)

N = 5000             # samples per batch
ROUNDS = 5000        # number of total rounds
WINDOW = 100         # rolling window
DRIFT_CYCLE = 200    # drift period
THETA = 0.22         # tuned decision threshold for recall ≈ 0.61
ADAPTIVE = False     # freeze controller during evaluation

# ----------------------------------------------------------
# Helper functions
# ----------------------------------------------------------
def drift_mu(round_id: int) -> float:
    """Cyclic drift pattern: no→mild→medium→strong."""
    phase = (round_id // DRIFT_CYCLE) % 4
    return [0.0, 0.5, 1.0, 2.0][phase]


def write_batch_csv(round_id: int, mu_shift: float) -> str:
    """Generate synthetic batch with given drift shift."""
    xb = np.random.normal(mu_shift, 1, N)
    yb = np.random.normal(5 + mu_shift, 2, N)
    path = f"sim_data/batch_{round_id}.csv"
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["x", "y"])
        w.writerows(zip(xb, yb))
    return path


# ----------------------------------------------------------
# 1) Reference dataset (baseline distribution)
# ----------------------------------------------------------
x = np.random.normal(0, 1, N)
y = np.random.normal(5, 2, N)
with open(ref_csv, "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["x", "y"])
    w.writerows(zip(x, y))
save_reference_stats(ref_stats_json, compute_reference_stats_from_csv(ref_csv))


# ----------------------------------------------------------
# 2) AMRC initialization
# ----------------------------------------------------------
cfg = AMRCConfig(alpha=2.0, beta=1.0, gamma=0.5, delta=0.5, lr=0.03)
amrc = AMRC(state_path, cfg)
amrc.state.update(w=[0.8, 0.25, 0.3, 0.15], theta=THETA, last_retrain_ts=0.0)

# ----------------------------------------------------------
# 3) Long-run 5000-round simulation (frozen AMRC)
# ----------------------------------------------------------
true_labels, decisions, scores, acc_history = [], [], [], []
start_time = time.time()

print(f"\n=== Running fixed-threshold evaluation for {ROUNDS} rounds (θ={THETA}) ===")

for r in range(1, ROUNDS + 1):
    mu = drift_mu(r)
    csv_path = write_batch_csv(r, mu)

    dec = amrc.decide(ref_stats_json, csv_path)
    drift = dec["signals"]["drift"]

    # Independent ground-truth: use injected drift (mu)
    MU_GT_THRESHOLD = 0.5  # mild+ drifts count as true drift
    gt = mu >= MU_GT_THRESHOLD

    true_labels.append(gt)
    decisions.append(dec["retrain"])
    scores.append(dec["score"])

    if ADAPTIVE:
        amrc.adapt(1.0 if gt else 0.0, dec["raw"]["cost_min"])
        if dec["retrain"]:
            amrc.mark_retrained()

    acc = np.mean(np.array(true_labels, int) == np.array(decisions, int))
    acc_history.append(acc)

    if r % 500 == 0:
        print(f"Round {r:04d}/{ROUNDS} | Drift={drift:.3f} | "
              f"Score={dec['score']:.3f} | Retrain? {dec['retrain']} | GT={gt}")

runtime = time.time() - start_time

# ----------------------------------------------------------
# 4) Final metrics
# ----------------------------------------------------------
true_arr = np.array(true_labels, int)
dec_arr = np.array(decisions, int)
TP = np.sum((dec_arr == 1) & (true_arr == 1))
FP = np.sum((dec_arr == 1) & (true_arr == 0))
FN = np.sum((dec_arr == 0) & (true_arr == 1))
TN = np.sum((dec_arr == 0) & (true_arr == 0))

precision = TP / (TP + FP + 1e-9)
recall    = TP / (TP + FN + 1e-9)
f1        = 2 * precision * recall / (precision + recall + 1e-9)
accuracy  = (TP + TN) / (TP + TN + FP + FN + 1e-9)
retrain_rate = np.mean(dec_arr)

print("\n===== AMRC Long-Run Accuracy Report =====")
print(f"θ (fixed)   : {THETA:.3f}")
print(f"Precision   : {precision:.3f}")
print(f"Recall      : {recall:.3f}")
print(f"F1-Score    : {f1:.3f}")
print(f"Accuracy    : {accuracy:.3f}")
print(f"RetrainRate : {retrain_rate:.3f}")
print(f"Runtime (s) : {runtime:.2f}")

# ----------------------------------------------------------
# 5) Visualization
# ----------------------------------------------------------
rolling_precision, rolling_recall, rolling_rounds = [], [], []
for end in range(WINDOW, ROUNDS + 1, WINDOW):
    s, e = end - WINDOW, end
    sub_true, sub_dec = true_arr[s:e], dec_arr[s:e]
    TPw = np.sum((sub_dec == 1) & (sub_true == 1))
    FPw = np.sum((sub_dec == 1) & (sub_true == 0))
    FNw = np.sum((sub_dec == 0) & (sub_true == 1))
    rolling_precision.append(TPw / (TPw + FPw + 1e-9))
    rolling_recall.append(TPw / (TPw + FNw + 1e-9))
    rolling_rounds.append(end)

plt.figure(figsize=(14,6))
plt.plot(range(1, ROUNDS + 1), scores, label="AMRC Score (R)", color="blue")
plt.axhline(THETA, color="red", linestyle="--", label=f"θ={THETA:.2f}")
plt.plot(range(1, ROUNDS + 1), acc_history, label="Cumulative Accuracy", color="orange")
plt.xlabel("Round")
plt.ylabel("Score / Accuracy")
plt.title(f"AMRC Long-Run Simulation ({ROUNDS} rounds, θ={THETA:.2f})")
plt.legend()
plt.grid(True, linestyle="--", alpha=0.5)
plt.tight_layout()
plt.savefig("sim_data/amrc_longrun_decisions.png", dpi=140)

plt.figure(figsize=(14,5))
plt.plot(rolling_rounds, rolling_precision, label="Rolling Precision")
plt.plot(rolling_rounds, rolling_recall, label="Rolling Recall")
plt.xlabel("Round")
plt.ylabel("Score")
plt.title(f"AMRC Rolling Precision / Recall (window={WINDOW}, θ={THETA:.2f})")
plt.legend()
plt.grid(True, linestyle="--", alpha=0.5)
plt.tight_layout()
plt.savefig("sim_data/amrc_longrun_rolling_pr_rc.png", dpi=140)

plt.show()

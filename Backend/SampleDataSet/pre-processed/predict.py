import pandas as pd
import joblib
import sys

def predict(input_path="dataset.csv", model_path="model.pkl", output_path="predictions.csv"):
    # Load model
    model = joblib.load(model_path)

    # Load data
    df = pd.read_csv(input_path)
    if "transaction_id" in df.columns:
        ids = df["transaction_id"]
    else:
        ids = range(len(df))

    X = df.drop(columns=[c for c in ["is_fraud", "transaction_id"] if c in df.columns])
    X = X.fillna(X.median())

    # Predict
    preds = model.predict(X)
    probs = None
    try:
        probs = model.predict_proba(X)[:, 1]
    except Exception:
        pass

    # Save output
    out = pd.DataFrame({
        "transaction_id": ids,
        "predicted": preds,
        "probability": probs if probs is not None else preds
    })
    out.to_csv(output_path, index=False)
    print(f"[Automesh.ai] Predictions saved to {output_path}")

if __name__ == "__main__":
    if len(sys.argv) != 4:
        print("Usage: python predict.py <input.csv> <model.pkl> <output.csv>")
    else:
        predict(sys.argv[1], sys.argv[2], sys.argv[3])

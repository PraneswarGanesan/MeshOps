import pandas as pd
import numpy as np
import joblib
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier

def train(input_path="dataset.csv", model_path="model.pkl"):
    # Load dataset
    df = pd.read_csv(input_path)

    # Drop identifiers
    if "transaction_id" in df.columns:
        df = df.drop(columns=["transaction_id"])

    # Features / target
    X = df.drop(columns=["is_fraud"])
    y = df["is_fraud"]

    # Handle NaNs
    X = X.fillna(X.median())

    # Train/test split
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42
    )

    # Try LogisticRegression → fallback RandomForest
    try:
        model = LogisticRegression(max_iter=500, random_state=42)
        model.fit(X_train, y_train)
    except Exception:
        model = RandomForestClassifier(n_estimators=100, random_state=42)
        model.fit(X_train, y_train)

    # Save trained model
    joblib.dump(model, model_path)
    print(f"[Automesh.ai] Model saved to {model_path}")

if __name__ == "__main__":
    import sys
    if len(sys.argv) > 2:
        train(sys.argv[1], sys.argv[2])
    else:
        train()

import pandas as pd
import joblib
import os

def run_predictions(base_dir):
    # Load dataset
    data_path = os.path.join(base_dir, "dataset.csv")
    df = pd.read_csv(data_path)

    X = df.drop("label", axis=1)
    y_true = df["label"]

    # Load trained model
    model_path = os.path.join(base_dir, "model.pkl")
    model = joblib.load(model_path)

    # Predict
    y_pred = model.predict(X)

    labels = sorted(list(set(y_true)))
    return y_true.tolist(), y_pred.tolist(), labels

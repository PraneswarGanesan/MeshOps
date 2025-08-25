import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
import joblib
import os

def train_model(base_dir):
    # Load dataset
    data_path = os.path.join(base_dir, "dataset.csv")
    df = pd.read_csv(data_path)

    X = df.drop("label", axis=1)
    y = df["label"]

    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

    # Train logistic regression model
    model = LogisticRegression()
    model.fit(X_train, y_train)

    # Save model
    model_path = os.path.join(base_dir, "model.pkl")
    joblib.dump(model, model_path)

    return model_path

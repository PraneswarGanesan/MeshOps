import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.pipeline import Pipeline
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix
import joblib

print("[INFO] Loading dataset...")
data = pd.read_csv("dataset.csv")

# Check expected columns
required_cols = {"Text", "Label"}
if not required_cols.issubset(data.columns):
    raise ValueError(f"Missing columns {required_cols} in dataset.csv")

# Features and labels
X = data["Text"].astype(str)
y = data["Label"].astype(str)

# Split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)

# Build pipeline: TF-IDF + Logistic Regression (multi-class)
model = Pipeline([
    ("tfidf", TfidfVectorizer(
        max_features=30000,
        ngram_range=(1, 2),
        stop_words="english"
    )),
    ("clf", LogisticRegression(
        solver="liblinear",
        multi_class="ovr",
        max_iter=2000,
        class_weight="balanced"
    ))
])

print("[INFO] Training model...")
model.fit(X_train, y_train)

# Save model
joblib.dump(model, "news_model.pkl")
print("[INFO] Model saved → news_model.pkl")

# Evaluate
print("\n=== Evaluation ===")
y_pred_train = model.predict(X_train)
y_pred_test = model.predict(X_test)

print("[TRAIN]")
print(classification_report(y_train, y_pred_train))

print("\n[TEST]")
print(classification_report(y_test, y_pred_test))

print("\nConfusion Matrix (Test):")
print(confusion_matrix(y_test, y_pred_test))

import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import LabelEncoder
import joblib

# --- Load dataset ---
data = pd.read_csv("dataset.csv")

# Clean missing values
data = data.dropna(subset=["review", "sentiment"])

# Encode sentiment (positive→1, negative→0)
le = LabelEncoder()
data["label"] = le.fit_transform(data["sentiment"].str.lower())

# Train-test split
X_train, X_test, y_train, y_test = train_test_split(
    data["review"], data["label"], test_size=0.2, random_state=42, stratify=data["label"]
)

# --- Build model pipeline ---
model = Pipeline([
    ("tfidf", TfidfVectorizer(
        max_features=20000,
        stop_words="english",
        ngram_range=(1,2)
    )),
    ("clf", LogisticRegression(
        max_iter=1000,
        solver="liblinear",
        class_weight="balanced"
    ))
])

# --- Train model ---
print("[INFO] Training sentiment model...")
model.fit(X_train, y_train)

# --- Save model ---
joblib.dump(model, "imdb_model.pkl")
print("[INFO] Model saved → imdb_model.pkl")

# --- Evaluate model ---
print("\n=== Evaluation ===")
y_pred_train = model.predict(X_train)
y_pred_test = model.predict(X_test)

print("\n[TRAIN]")
print(classification_report(y_train, y_pred_train, target_names=le.classes_))
print("\n[TEST]")
print(classification_report(y_test, y_pred_test, target_names=le.classes_))
print("\nConfusion Matrix (Test):")
print(confusion_matrix(y_test, y_pred_test))

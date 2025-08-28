import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.linear_model import LogisticRegression
import joblib

# Load dataset
data = pd.read_csv("dataset.csv")

# Features and target
X = data[["amount", "duration", "age", "is_international"]]
y = data["is_fraud"]

# Train-test split
X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.3, random_state=42
)

# Train Logistic Regression (tuned for better accuracy)
model = LogisticRegression(max_iter=2000, solver="liblinear")
model.fit(X_train, y_train)

# Save model
joblib.dump(model, "fraud_model.pkl")

# Evaluate
train_score = model.score(X_train, y_train)
test_score = model.score(X_test, y_test)

print(f"Training Accuracy: {train_score:.2f}")
print(f"Test Accuracy: {test_score:.2f}")

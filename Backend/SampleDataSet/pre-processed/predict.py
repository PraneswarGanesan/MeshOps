import pandas as pd
import joblib

# Load trained model
model = joblib.load("fraud_model.pkl")

# Example batch test inputs
test_data = pd.DataFrame([
    {"amount": 120.5, "duration": 30, "age": 25, "is_international": 0},   # Legit
    {"amount": 2500, "duration": 300, "age": 45, "is_international": 1},  # Fraud
    {"amount": 60, "duration": 5, "age": 21, "is_international": 0},      # Legit
    {"amount": 4000, "duration": 500, "age": 55, "is_international": 1},  # Fraud
    {"amount": 150, "duration": 20, "age": 30, "is_international": 0},    # Legit
    {"amount": 3200, "duration": 420, "age": 49, "is_international": 1},  # Fraud
    {"amount": 75, "duration": 8, "age": 22, "is_international": 0},      # Legit
    {"amount": 1800, "duration": 250, "age": 38, "is_international": 1},  # Fraud
    {"amount": 55, "duration": 3, "age": 19, "is_international": 0},      # Legit
    {"amount": 2700, "duration": 370, "age": 42, "is_international": 1},  # Fraud
])

# Predict
preds = model.predict(test_data)

# Show results
test_data["prediction"] = ["FRAUD 🚨" if p == 1 else "Legit ✅" for p in preds]
print(test_data)

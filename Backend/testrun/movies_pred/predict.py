import pandas as pd
import joblib

# --- Load model ---
model = joblib.load("imdb_model.pkl")

# --- Example test reviews ---
test_data = pd.DataFrame({
    "review": [
        "The movie was absolutely wonderful and the acting was brilliant!",
        "I hated this film. It was a complete waste of time.",
        "The story was okay but the ending felt rushed.",
        "One of the best performances I have ever seen!",
        "Terrible sound design and weak dialogue."
    ]
})

# --- Predict ---
preds = model.predict(test_data["review"])
labels = ["negative" if p == 0 else "positive" for p in preds]

# --- Display results ---
test_data["prediction"] = labels
print(test_data)

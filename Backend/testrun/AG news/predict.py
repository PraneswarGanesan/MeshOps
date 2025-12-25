import joblib

# Load trained model
model = joblib.load("news_model.pkl")

def predict_category(text: str):
    """Predict the AG News category for input text."""
    pred = model.predict([text])[0]
    return pred

if __name__ == "__main__":
    print("=== AG News Prediction Demo ===")
    samples = [
        "NASA discovers water on Mars for the first time in decades.",
        "Apple releases new MacBook Pro with M3 chip and improved display.",
        "Manchester United wins Premier League after stunning comeback.",
        "Global markets fall as oil prices reach new highs.",
    ]
    for s in samples:
        label = predict_category(s)
        print(f"Input: {s}\n → Predicted Label: {label}\n")

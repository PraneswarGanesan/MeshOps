import pandas as pd
import re
import os

RAW_PATH = "IMDB Dataset.csv"        # Original Kaggle file
OUT_PATH = "dataset.csv"             # Cleaned output for MeshOps

def clean_text(text: str) -> str:
    """Remove HTML tags, punctuation noise, and normalize spaces."""
    if not isinstance(text, str):
        return ""
    text = re.sub(r"<br\s*/?>", " ", text)           # remove <br> and <br />
    text = re.sub(r"[^A-Za-z0-9.,!? ]+", " ", text)  # keep basic punctuation
    text = re.sub(r"\s+", " ", text).strip()         # collapse spaces
    return text

def main():
    if not os.path.exists(RAW_PATH):
        raise FileNotFoundError(f"Raw file not found: {RAW_PATH}")

    print(f"[INFO] Loading {RAW_PATH} ...")
    df = pd.read_csv(RAW_PATH)

    # Basic cleaning
    df = df.dropna(subset=["review", "sentiment"])
    df["review"] = df["review"].apply(clean_text)
    df["sentiment"] = df["sentiment"].str.lower().str.strip()

    # Balance positive/negative reviews (optional, keeps same total)
    min_count = df["sentiment"].value_counts().min()
    balanced = (
        df.groupby("sentiment", group_keys=False)
          .apply(lambda x: x.sample(min_count, random_state=42))
          .reset_index(drop=True)
    )

    print(f"[INFO] Final dataset size: {len(balanced):,} rows")
    print(balanced["sentiment"].value_counts())

    # Save cleaned dataset
    balanced.to_csv(OUT_PATH, index=False, encoding="utf-8")
    print(f"[SUCCESS] Cleaned dataset saved → {OUT_PATH}")

if __name__ == "__main__":
    main()

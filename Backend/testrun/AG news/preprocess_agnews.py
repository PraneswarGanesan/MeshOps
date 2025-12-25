import pandas as pd

# Load both splits exactly as they are
train = pd.read_csv("train.csv")
test = pd.read_csv("test.csv")

# Combine both sets
df = pd.concat([train, test], axis=0).reset_index(drop=True)

# Ensure correct column names
df.columns = ["Class Index", "Title", "Description"]

# Map numeric class to readable label for reference (optional)
label_map = {
    1: "World",
    2: "Sports",
    3: "Business",
    4: "Sci/Tech"
}
df["Label"] = df["Class Index"].map(label_map)

# Create an auxiliary text column for model input
df["Text"] = (df["Title"].astype(str) + " " + df["Description"].astype(str)).str.strip()

# Reorder columns for clarity
df = df[["Class Index", "Label", "Title", "Description", "Text"]]

# Save final dataset
df.to_csv("dataset.csv", index=False, encoding="utf-8")

print("[INFO] ✅ Combined dataset created successfully → dataset.csv")
print(f"[INFO] Total records: {len(df)}")
print("[INFO] Columns:", list(df.columns))
print(df.head(3))

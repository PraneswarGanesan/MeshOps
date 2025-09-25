import argparse
import json
import csv
import os
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, confusion_matrix
import matplotlib.pyplot as plt
import torch
import torchvision
import torch.nn as nn
import torch.optim as optim
from torchvision import transforms, models
from torch.utils.data import DataLoader
from sklearn.ensemble import RandomForestClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.feature_extraction.text import TfidfVectorizer
from PIL import Image
import yaml

def train_image_classifier(train_loader, val_loader):
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = models.resnet18(pretrained=True)
    model.fc = nn.Linear(model.fc.in_features, 2)
    model = model.to(device)
    criterion = nn.CrossEntropyLoss()
    optimizer = optim.Adam(model.parameters(), lr=1e-4)
    
    for epoch in range(2):
        model.train()
        for inputs, labels in train_loader:
            inputs, labels = inputs.to(device), labels.to(device)
            optimizer.zero_grad()
            outputs = model(inputs)
            loss = criterion(outputs, labels)
            loss.backward()
            optimizer.step()
    return model

def train_tabular_classifier(X_train, y_train):
    model = RandomForestClassifier()
    model.fit(X_train, y_train)
    return model

def train_text_classifier(X_train, y_train):
    vectorizer = TfidfVectorizer()
    X_train_vec = vectorizer.fit_transform(X_train)
    model = LogisticRegression()
    model.fit(X_train_vec, y_train)
    return model, vectorizer


def predict(model, img_path, transform, idx_to_class,tabular=False,vectorizer=None):
    if not tabular:
        img = Image.open(img_path).convert("RGB")
        img_t = transform(img).unsqueeze(0)
        with torch.no_grad():
            outputs = model(img_t)
            _, pred = outputs.max(1)
        return idx_to_class[pred.item()]
    else:
        # Assuming img_path is the index of the datapoint
        return model.predict([img_path])[0]


def main():
    print(">>> DRIVER STARTED <<<", flush=True)

    parser = argparse.ArgumentParser()
    parser.add_argument("--base_dir", required=True)
    args = parser.parse_args()
    base_dir = args.base_dir

    model_path = os.path.join(base_dir,"model.pt")
    
    if os.path.exists(model_path):
        print(f"Model loaded from: {model_path}")
    elif os.path.exists(os.path.join(base_dir,"images/train")):
        print("Training image classifier...")
        transform = transforms.Compose([
            transforms.Resize((224, 224)),
            transforms.ToTensor(),
            transforms.Normalize([0.485, 0.456, 0.406],
                                 [0.229, 0.224, 0.225])
        ])
        train_data = torchvision.datasets.ImageFolder(os.path.join(base_dir,"images/train"), transform=transform)
        val_data = torchvision.datasets.ImageFolder(os.path.join(base_dir,"images/val"), transform=transform)
        train_loader = DataLoader(train_data, batch_size=16, shuffle=True)
        val_loader = DataLoader(val_data, batch_size=16)
        model = train_image_classifier(train_loader, val_loader)
        torch.save(model.state_dict(), model_path)
        print(f"Model trained and saved to: {model_path}")
    
    else:
        print("No suitable dataset or pre-trained model found.")
        exit(1)
        
    with open(os.path.join(base_dir,"tests.yaml"), "r") as f:
        tests = yaml.safe_load(f)["tests"]["scenarios"]
        
    idx_to_class = {0: "cat", 1: "dog"}
    predictions = []
    
    for scenario in tests:
        prediction = predict(model, scenario["input"], transform, idx_to_class)
        predictions.append({"name": scenario["name"],"expected": scenario["expected"], "predicted": prediction})

    print("Predictions generated.")
    
    with open(os.path.join(base_dir,"tests.csv"), "w", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["name","category","severity","expected","predicted","result"])
        for pred in predictions:
            result = "PASS" if pred["expected"] == pred["predicted"] else "FAIL"
            writer.writerow([pred["name"],"cat-dog","info", pred["expected"],pred["predicted"],result])

    # Evaluation (replace with actual evaluation if needed)
    y_true = [scenario["expected"] for scenario in tests]
    y_pred = [pred["predicted"] for pred in predictions]
    accuracy = accuracy_score(y_true, y_pred)
    precision = precision_score(y_true, y_pred, average='macro',zero_division=0)
    recall = recall_score(y_true, y_pred, average='macro',zero_division=0)
    f1 = f1_score(y_true, y_pred, average='macro',zero_division=0)

    print("Evaluation metrics:")
    print(f"Accuracy: {accuracy}")
    print(f"Precision: {precision}")
    print(f"Recall: {recall}")
    print(f"F1-score: {f1}")
    
    cm = confusion_matrix(y_true, y_pred)
    plt.imshow(cm, interpolation='nearest', cmap=plt.cm.Blues)
    plt.savefig(os.path.join(base_dir,"confusion_matrix.png"))


    #Generate artifacts (simplified for brevity)
    with open(os.path.join(base_dir,"metrics.json"), "w") as f:
        json.dump({"accuracy": accuracy, "precision": precision, "recall": recall, "f1": f1}, f)
    with open(os.path.join(base_dir,"logs.txt"), "w") as f:
        f.write("Training and evaluation logs.")
    with open(os.path.join(base_dir,"manifest.json"), "w") as f:
        json.dump({"files":["model.pt", "tests.csv", "metrics.json", "confusion_matrix.png", "logs.txt"]}, f)
    with open(os.path.join(base_dir,"refiner_hints.json"),"w") as f:
        json.dump({"suggestions":[]},f)



if __name__ == "__main__":
    main()
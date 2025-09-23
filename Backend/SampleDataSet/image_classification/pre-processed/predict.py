import sys
import torch
from torchvision import transforms, models
from PIL import Image

# 🔹 Load model
device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model = models.resnet18(pretrained=False)
model.fc = torch.nn.Linear(model.fc.in_features, 2)  # 2 classes
model.load_state_dict(torch.load("model.pt", map_location=device))
model.eval()

# 🔹 Labels
idx_to_class = {0: "cat", 1: "dog"}

# 🔹 Transform
transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406],
                         [0.229, 0.224, 0.225])
])

# 🔹 Predict on given image
if len(sys.argv) < 2:
    print("Usage: python predict.py <image_path>")
    sys.exit(1)

img_path = sys.argv[1]
img = Image.open(img_path).convert("RGB")
img_t = transform(img).unsqueeze(0)

with torch.no_grad():
    outputs = model(img_t)
    _, pred = outputs.max(1)

print(f"Prediction for {img_path}: {idx_to_class[pred.item()]}")

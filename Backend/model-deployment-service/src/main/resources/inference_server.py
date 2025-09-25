#!/usr/bin/env python3
"""
Flask inference server for deployed models
This script is deployed to EC2 instances for model serving
"""

import os
import sys
import json
import time
import logging
import traceback
from datetime import datetime
from flask import Flask, request, jsonify
import boto3
import numpy as np
import pandas as pd
from sklearn.externals import joblib
import pickle
import torch
import torchvision.transforms as transforms
from PIL import Image
import io

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = Flask(__name__)

# Global model and configuration
model = None
model_config = {}
s3_client = boto3.client('s3')

def load_model_from_s3(bucket, model_path):
    """Load model artifacts from S3"""
    global model, model_config
    
    try:
        # Download model files
        local_model_dir = '/tmp/model'
        os.makedirs(local_model_dir, exist_ok=True)
        
        # List all model files
        response = s3_client.list_objects_v2(Bucket=bucket, Prefix=model_path)
        
        if 'Contents' not in response:
            logger.error(f"No model files found at {model_path}")
            return False
        
        for obj in response['Contents']:
            key = obj['Key']
            filename = os.path.basename(key)
            local_path = os.path.join(local_model_dir, filename)
            
            logger.info(f"Downloading {key} to {local_path}")
            s3_client.download_file(bucket, key, local_path)
        
        # Load model based on available files
        model_files = os.listdir(local_model_dir)
        logger.info(f"Available model files: {model_files}")
        
        # Try different model formats
        if 'model.pkl' in model_files:
            # Scikit-learn model
            with open(os.path.join(local_model_dir, 'model.pkl'), 'rb') as f:
                model = pickle.load(f)
            model_config['type'] = 'sklearn'
            
        elif 'model.pth' in model_files:
            # PyTorch model
            model = torch.load(os.path.join(local_model_dir, 'model.pth'), map_location='cpu')
            model.eval()
            model_config['type'] = 'pytorch'
            
        elif 'model.joblib' in model_files:
            # Joblib model
            model = joblib.load(os.path.join(local_model_dir, 'model.joblib'))
            model_config['type'] = 'sklearn'
            
        else:
            logger.error("No supported model format found")
            return False
        
        # Load configuration if available
        config_path = os.path.join(local_model_dir, 'config.json')
        if os.path.exists(config_path):
            with open(config_path, 'r') as f:
                model_config.update(json.load(f))
        
        logger.info(f"Model loaded successfully: {model_config}")
        return True
        
    except Exception as e:
        logger.error(f"Error loading model: {e}")
        logger.error(traceback.format_exc())
        return False

def preprocess_input(data, input_type='tabular'):
    """Preprocess input data based on model type"""
    
    if input_type == 'image':
        # Handle image input
        if 'image' in data:
            # Base64 encoded image
            import base64
            image_data = base64.b64decode(data['image'])
            image = Image.open(io.BytesIO(image_data))
            
            # Standard image preprocessing
            transform = transforms.Compose([
                transforms.Resize((224, 224)),
                transforms.ToTensor(),
                transforms.Normalize(mean=[0.485, 0.456, 0.406], 
                                   std=[0.229, 0.224, 0.225])
            ])
            
            return transform(image).unsqueeze(0)
        
    elif input_type == 'text':
        # Handle text input
        return data.get('text', '')
        
    else:
        # Handle tabular data
        # Convert to DataFrame or numpy array
        if isinstance(data, dict):
            # Single prediction
            return pd.DataFrame([data])
        elif isinstance(data, list):
            # Batch prediction
            return pd.DataFrame(data)
        
    return data

def make_prediction(input_data):
    """Make prediction using the loaded model"""
    global model, model_config
    
    if model is None:
        raise ValueError("Model not loaded")
    
    try:
        model_type = model_config.get('type', 'sklearn')
        input_type = model_config.get('input_type', 'tabular')
        
        # Preprocess input
        processed_input = preprocess_input(input_data, input_type)
        
        # Make prediction based on model type
        if model_type == 'pytorch':
            with torch.no_grad():
                if input_type == 'image':
                    output = model(processed_input)
                    probabilities = torch.softmax(output, dim=1)
                    confidence, predicted = torch.max(probabilities, 1)
                    
                    return {
                        'prediction': predicted.item(),
                        'confidence': confidence.item(),
                        'probabilities': probabilities.squeeze().tolist()
                    }
                else:
                    # Convert to tensor if needed
                    if not isinstance(processed_input, torch.Tensor):
                        processed_input = torch.tensor(processed_input.values, dtype=torch.float32)
                    
                    output = model(processed_input)
                    return {'prediction': output.tolist()}
                    
        else:
            # Scikit-learn or similar
            if hasattr(model, 'predict_proba'):
                # Classification with probabilities
                prediction = model.predict(processed_input)[0]
                probabilities = model.predict_proba(processed_input)[0]
                confidence = max(probabilities)
                
                return {
                    'prediction': int(prediction) if isinstance(prediction, np.integer) else prediction,
                    'confidence': float(confidence),
                    'probabilities': probabilities.tolist()
                }
            else:
                # Regression or classification without probabilities
                prediction = model.predict(processed_input)[0]
                return {
                    'prediction': float(prediction) if isinstance(prediction, np.number) else prediction,
                    'confidence': 1.0
                }
                
    except Exception as e:
        logger.error(f"Prediction error: {e}")
        logger.error(traceback.format_exc())
        raise

@app.route('/health', methods=['GET'])
def health_check():
    """Health check endpoint"""
    return jsonify({
        'status': 'healthy',
        'model_loaded': model is not None,
        'timestamp': datetime.now().isoformat(),
        'config': model_config
    })

@app.route('/predict', methods=['POST'])
def predict():
    """Main prediction endpoint"""
    start_time = time.time()
    
    try:
        # Get input data
        data = request.get_json()
        
        if not data:
            return jsonify({
                'error': 'No input data provided'
            }), 400
        
        # Make prediction
        result = make_prediction(data)
        
        # Calculate latency
        latency_ms = (time.time() - start_time) * 1000
        
        # Log metrics
        logger.info(f"Prediction completed in {latency_ms:.2f}ms")
        
        # Return standardized response
        response = {
            'prediction': result.get('prediction'),
            'confidence': result.get('confidence', 1.0),
            'timestamp': int(time.time()),
            'latency_ms': round(latency_ms, 2)
        }
        
        # Add probabilities if available
        if 'probabilities' in result:
            response['probabilities'] = result['probabilities']
        
        return jsonify(response)
        
    except Exception as e:
        logger.error(f"Prediction failed: {e}")
        return jsonify({
            'error': str(e),
            'timestamp': int(time.time())
        }), 500

@app.route('/metrics', methods=['GET'])
def get_metrics():
    """Get inference metrics"""
    # This could be enhanced to return actual metrics
    return jsonify({
        'requests_total': 0,
        'requests_per_second': 0,
        'average_latency_ms': 0,
        'error_rate': 0,
        'uptime_seconds': 0
    })

if __name__ == '__main__':
    # Get configuration from environment
    bucket = os.environ.get('S3_BUCKET')
    model_path = os.environ.get('MODEL_PATH')
    port = int(os.environ.get('PORT', 5000))
    
    if not bucket or not model_path:
        logger.error("S3_BUCKET and MODEL_PATH environment variables are required")
        sys.exit(1)
    
    # Load model
    logger.info(f"Loading model from s3://{bucket}/{model_path}")
    if not load_model_from_s3(bucket, model_path):
        logger.error("Failed to load model")
        sys.exit(1)
    
    # Start server
    logger.info(f"Starting inference server on port {port}")
    app.run(host='0.0.0.0', port=port, debug=False)

# Automesh.ai 🚀  
AI-Powered MLOps Testing & Deployment Platform

We are in the devlopment phase

![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)
![Tech Stack](https://img.shields.io/badge/TechStack-Python%2C%20SpringBoot%2C%20React%2C%20AWS-orange)
![Status](https://img.shields.io/badge/Status-Active-green)

## 🌐 Overview

**Automesh.ai** is an end-to-end cloud-based MLOps testing platform that leverages the power of **Large Language Models (LLMs)** to automatically generate, test, and validate ML models in isolated sandbox environments. It replaces traditional testers by providing automated **unit testing**, **behavioral testing**, **integration testing**, and **feedback loop integration**—all in one intelligent dashboard.

This platform is designed to help ML practitioners, students, and enterprises test their models efficiently without writing a single line of test code manually.

---

## 🔍 Features

- ✅ **Automated Unit Test Case Generation** using LLMs (editable by user)
- 🧠 **Behavioral Testing** using LLM-based predictions and edge-case generation
- 🔗 **Integration Testing** with execution tracking and performance graphs
- 💬 **Feedback Loop** support for fine-tuning test cases and models
- ☁️ **Cloud Sandbox** environment (2GB free) for model training & testing
- 📊 Real-time Metrics: Confusion Matrix, Accuracy, Loss graphs, etc.
- 💰 **Storage-Based Tier System** with optional payment model
- 🛡️ **AES + PSO-Based Secure Deployment** optimization

---

## 🏗️ Tech Stack

| Layer              | Technology                         |
|--------------------|-------------------------------------|
| Frontend           | React.js (Tailwind CSS + Charts)    |
| Backend            | Spring Boot (Java) + Python         |
| AI/LLM Layer       | Gemini API (LLM)                    |
| Storage/Sandbox    | AWS S3 + EC2 (sandbox environments) |
| Deployment Logic   | PSO (Particle Swarm Optimization)   |
| Database           | MongoDB or PostgreSQL               |
| Security           | AES-based Encryption                |
| Architecture       | Microservices + API Gateway         |

---

## 📁 Modules

### 1️⃣ Unit Testing (Sprint Day 1)
- User uploads code + dataset
- LLM generates test code
- User can edit/approve tests
- Tests executed in sandbox
- Results stored in DB

### 2️⃣ Behavioral Testing (Sprint Day 2)
- LLM predicts likely edge-cases
- Behavior simulated with inputs
- Deviations captured
- Report generated per model

### 3️⃣ Integration Testing + Feedback Loop
- Combined test pipeline for models
- Confusion Matrix, Loss/Accuracy chart
- Feedback input from user used to regenerate/improve tests

---

## 🔒 Storage & Sandbox Policy

- ✅ 2GB Free Cloud Sandbox
- 🪙 Pay-as-you-grow model:
  - +100MB = ₹60
  - +500MB = ₹125
  - +1GB = ₹445
  - +5GB = ₹2,225

---

## 🛠️ How to Run

1. Clone the repo:
   ```bash
   git clone https://github.com/yourusername/automesh.ai.git

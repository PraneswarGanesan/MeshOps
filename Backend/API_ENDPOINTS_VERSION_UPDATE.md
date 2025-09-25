# API Endpoints - Version Control Update

## 🎯 **ALL ENDPOINTS NOW REQUIRE versionLabel**

Every endpoint that works with behaviour testing artifacts now requires an explicit `versionLabel` parameter in the URL path. This enforces strict version control where only the Retrain Service can create versions.

## 📋 **UPDATED API ENDPOINTS**

### **1. PlanController** ✅
**Base Path**: `/api/plans`

| Method | OLD Endpoint | NEW Endpoint | Description |
|--------|-------------|--------------|-------------|
| POST | `/{username}/{projectName}/generate` | `/{username}/{projectName}/{versionLabel}/generate` | Generate driver.py + tests.yaml |
| GET | `/{username}/{projectName}/tests` | `/{username}/{projectName}/{versionLabel}/tests` | List all tests |
| POST | `/{username}/{projectName}/tests/new` | `/{username}/{projectName}/{versionLabel}/tests/new` | Generate new tests.yaml |
| POST | `/{username}/{projectName}/tests/activate` | `/{username}/{projectName}/{versionLabel}/tests/activate` | Activate tests |

**Example Usage**:
```bash
# Generate initial plan for v0
POST /api/plans/john/cat_dog_classifier/v0/generate

# Generate plan for v1 (with existing model)
POST /api/plans/john/cat_dog_classifier/v1/generate

# List tests in v1
GET /api/plans/john/cat_dog_classifier/v1/tests
```

### **2. RefinerController** ✅
**Base Path**: `/api/refiner`

| Method | OLD Endpoint | NEW Endpoint | Description |
|--------|-------------|--------------|-------------|
| POST | `/{username}/{projectName}/refine` | `/{username}/{projectName}/{versionLabel}/refine` | Refine and activate tests |

**Example Usage**:
```bash
# Refine tests within v1
POST /api/refiner/john/cat_dog_classifier/v1/refine?runId=123&autoRun=true
Body: { "userFeedback": "Add more edge cases for blurry images" }
```

### **3. ScenarioController** ✅
**Base Path**: `/api/scenarios`

| Method | OLD Endpoint | NEW Endpoint | Description |
|--------|-------------|--------------|-------------|
| POST | `/{username}/{projectName}/prompts` | `/{username}/{projectName}/{versionLabel}/prompts` | Save scenario prompt |
| GET | `/{username}/{projectName}/prompts` | `/{username}/{projectName}/{versionLabel}/prompts` | List prompts |
| DELETE | `/{username}/{projectName}/prompts` | `/{username}/{projectName}/{versionLabel}/prompts` | Clear prompts |

**Example Usage**:
```bash
# Save prompt for v1
POST /api/scenarios/john/cat_dog_classifier/v1/prompts
Body: { "message": "Add tests for low-light conditions", "runId": 456 }

# List prompts for v1
GET /api/scenarios/john/cat_dog_classifier/v1/prompts?limit=10

# Clear prompts for v1
DELETE /api/scenarios/john/cat_dog_classifier/v1/prompts
```

### **4. RunController** ✅ (No Changes Needed)
**Base Path**: `/api/runs`

These endpoints work with `runId` which is already version-specific:
- `POST /runs/start` - Starts run for specific version
- `GET /runs/{runId}/status` - Get run status
- `POST /runs/{runId}/poll` - Poll run status
- `GET /runs/{runId}/artifacts` - List artifacts
- `GET /runs/{runId}/console` - Get console output

### **5. ProjectController** ✅ (No Changes Needed)
**Base Path**: `/api/projects`

These endpoints are for project management, not version-specific:
- `POST /projects/ensure` - Create/ensure project
- `GET /projects/{username}/{projectName}` - Get project details

## 🔄 **VERSION LIFECYCLE API FLOW**

### **Phase 1: Initial Setup (v0)**
```bash
# 1. Create project (if needed)
POST /api/projects/ensure
Body: { "username": "john", "projectName": "cat_dog_classifier", "s3Prefix": "john/cat_dog_classifier" }

# 2. Generate initial plan for v0
POST /api/plans/john/cat_dog_classifier/v0/generate
Body: { "brief": "Create a cat vs dog image classifier" }
```

### **Phase 2: First Training (v1 Creation by Retrain Service)**
```bash
# Retrain Service creates v1 with model.pkl
# (This is handled by Retrain Service, not exposed via these APIs)
```

### **Phase 3: Work within v1**
```bash
# 1. Generate enhanced plan for v1 (model-aware)
POST /api/plans/john/cat_dog_classifier/v1/generate
Body: { "brief": "Improve accuracy for edge cases" }

# 2. Add scenario feedback
POST /api/scenarios/john/cat_dog_classifier/v1/prompts
Body: { "message": "Add tests for blurry images", "runId": 123 }

# 3. Refine tests based on feedback
POST /api/refiner/john/cat_dog_classifier/v1/refine?runId=123&autoRun=true
Body: { "userFeedback": "Focus on low-light conditions" }

# 4. List current tests
GET /api/plans/john/cat_dog_classifier/v1/tests
```

### **Phase 4: Next Iteration (v2 Creation)**
```bash
# Retrain Service creates v2
# Then all services target v2:
POST /api/plans/john/cat_dog_classifier/v2/generate
POST /api/refiner/john/cat_dog_classifier/v2/refine
POST /api/scenarios/john/cat_dog_classifier/v2/prompts
```

## 🛡️ **ENFORCEMENT MECHANISMS**

### **1. Required versionLabel Parameter**
All endpoints now require `versionLabel` in the URL path:
```java
@PostMapping("/{username}/{projectName}/{versionLabel}/generate")
public Mono<Map<String, String>> generateInitialPlan(
        @PathVariable String username,
        @PathVariable String projectName,
        @PathVariable String versionLabel,  // <-- REQUIRED
        @RequestBody GeneratePlanRequest req) {
    // ...
}
```

### **2. Service-Level Validation**
All services validate versionLabel presence:
```java
if (!StringUtils.hasText(versionLabel)) {
    throw new IllegalArgumentException("versionLabel is required - [ServiceName] cannot create versions");
}
```

### **3. Version-Scoped Operations**
All operations work within the specified version:
```java
String versionBase = S3KeyUtil.join(root, "artifacts/versions", versionLabel);
log.info("[ServiceName] working within version: {} at path: {}", versionLabel, versionBase);
```

## 📊 **BREAKING CHANGES SUMMARY**

| Service | Endpoints Changed | Impact |
|---------|------------------|--------|
| **PlanController** | 4 endpoints | All plan operations now version-scoped |
| **RefinerController** | 1 endpoint | Refinement operations now version-scoped |
| **ScenarioController** | 3 endpoints | Scenario management now version-scoped |
| **RunController** | 0 endpoints | No changes (already version-aware via runId) |
| **ProjectController** | 0 endpoints | No changes (project-level operations) |

## 🚀 **IMMEDIATE BENEFITS**

1. **✅ Strict Version Control**: No accidental version creation
2. **✅ Clear API Contract**: Version must be explicitly specified
3. **✅ Reproducible Operations**: All operations are version-scoped
4. **✅ Model Continuity**: Services are aware of existing models in versions
5. **✅ Proper Lifecycle**: v0 → v1 → v2 progression enforced
6. **✅ Error Prevention**: Missing versionLabel throws clear exceptions

## 🎯 **RESULT**

All behaviour-testing APIs now enforce strict version control. The frontend and any API consumers must explicitly specify which version they want to work with, ensuring proper lifecycle management and preventing accidental version creation by non-Retrain services.

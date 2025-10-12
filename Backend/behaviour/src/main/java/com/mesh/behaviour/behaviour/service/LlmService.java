// src/main/java/com/mesh/behaviour/behaviour/service/LlmService.java
package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final S3Service s3;
    private final WebClient webClient = WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(
                    HttpClient.create()
                            .responseTimeout(Duration.ofMinutes(5))                // overall response timeout
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)   // connect timeout
                            .doOnConnected(conn ->
                                    conn.addHandlerLast(new ReadTimeoutHandler(300))   // 5 min read
                                            .addHandlerLast(new WriteTimeoutHandler(300))  // 5 min write
                            )
            ))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.gemini.apiKey}")
    private String apiKey;

    @Value("${llm.gemini.url}")
    private String url;

    /* ================= TESTS ONLY ================= */

    public Map<String, String> generateTestsOnlyToS3(String brief,
                                                     String context,
                                                     String baseKey,
                                                     String label) {
        requireKeys();

        String root = deriveProjectRootFromAnyKey(baseKey);
        String versionBase = resolveVersionBaseKey(root, label);

        // always rebuild augmented context
        String augmentedContext = buildS3AugmentedContext(root, versionBase);

        String prompt = buildTestsOnlyPrompt(brief, augmentedContext);
        log.info("=== [LLM] Prompt for tests.yaml (len={}) ===\n{}", prompt.length(), prompt);

        String geminiResp = callGemini(prompt).block();
        if (!StringUtils.hasText(geminiResp)) {
            throw new RuntimeException("Gemini returned empty response for tests.yaml generation");
        }

        String combined = extractAllTextFromGemini(geminiResp);
        log.info("=== [LLM] Raw Gemini Response ===\n{}", combined);

        // Try fenced YAML first
        String tests = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);

        // If no fenced block, but raw starts with tests:, accept directly
        if (!StringUtils.hasText(tests) && combined.trim().startsWith("tests:")) {
            tests = combined.trim();
        }

        // If still empty, fallback
        if (!StringUtils.hasText(tests)) {
            tests = "tests:\n  - name: fallback\n    run: echo 'LLM failed to generate tests.yaml'\n";
        }

        String versionKey = S3KeyUtil.join(baseKey, label + ".yaml");
        s3.putString(versionKey, cleanBlock(tests), "text/yaml");

        return Map.of("versionKey", versionKey);
    }


    /* ================= DRIVER + TESTS ================= */

    public Map<String, String> generateDriverUsingAdaptivePrompt(String brief,
                                                                 String context,
                                                                 String baseKey,
                                                                 String label,
                                                                 boolean withTests) {
        requireKeys();

        String prompt;
        if (withTests) {
            prompt = buildDriverAndTestsPrompt(context, brief);
        } else {
            prompt = String.format("""
You are generating ONLY driver.py for an ML project.

CONTEXT:
%s

Rules:
- Output ONLY Python code (no markdown, no prose).
- Must include training and prediction logic.
- Must print required evaluation lines.

USER BRIEF:
%s
""", context == null ? "" : context, brief == null ? "" : brief);
        }

        log.info("=== [LLM] Adaptive Prompt ===\n{}", prompt);

        String geminiResp = callGemini(prompt).block();
        if (!StringUtils.hasText(geminiResp)) {
            throw new RuntimeException("Gemini returned empty response for adaptive driver generation");
        }

        String combined = extractAllTextFromGemini(geminiResp);
        log.info("=== [LLM] Raw Gemini Response ===\n{}", combined);

        String driver = firstGroup("(?s)```(?:python|py)\\s+(.*?)\\s*```", combined);
        if (!StringUtils.hasText(driver)) {
            driver = "#!/usr/bin/env python3\nprint('LLM failed to generate driver.py')";
        }

        String driverKey = S3KeyUtil.join(baseKey, label + "_driver.py");
        s3.putString(driverKey, cleanBlock(driver), "text/x-python");

        Map<String, String> out = new HashMap<>();
        out.put("driverKey", driverKey);

        if (withTests) {
            String tests = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);
            if (!StringUtils.hasText(tests)) {
                tests = "tests:\n  - name: fallback\n    run: echo 'LLM failed to generate tests.yaml'\n";
            }
            String testsKey = S3KeyUtil.join(baseKey, label + "_tests.yaml");
            s3.putString(testsKey, cleanBlock(tests), "text/yaml");
            out.put("testsKey", testsKey);
        }

        return out;
    }

    public Mono<Map<String, String>> generateDriverForVersion(
            String brief, String baseKey, String versionPrefix
    ) {
        requireKeys();
        if (!StringUtils.hasText(baseKey)) throw new IllegalArgumentException("baseKey required");
        if (!StringUtils.hasText(versionPrefix)) throw new IllegalArgumentException("versionPrefix required");

        String root = normalizeBaseKey(baseKey); // root: {username}/{project}
        // versionPrefix may be just 'v2' or a path containing 'artifacts/versions/v2' – normalize to 'v2'
        String vLabel = (versionPrefix == null ? "" : versionPrefix.trim());
        if (vLabel.startsWith("s3://")) vLabel = S3KeyUtil.keyOf(vLabel);
        // Extract last 'vN' occurrence
        Matcher vm = Pattern.compile("v\\d+").matcher(vLabel);
        String onlyV = null; while (vm.find()) { onlyV = vm.group(); }
        if (!StringUtils.hasText(onlyV)) throw new IllegalArgumentException("versionLabel required");
        String versionBaseKey = S3KeyUtil.join(root, "artifacts/versions", onlyV, "");

        log.info("[generate] root='{}', versionBase='{}'", root, versionBaseKey);
        String augmentedContext = buildS3AugmentedContext(root, versionBaseKey);
        String prompt = buildDriverAndTestsPrompt(augmentedContext, brief);

        log.info("=== [LLM] Final Prompt Sent to Gemini ===\n{}", prompt);

        return callGemini(prompt).map(resp -> {
            log.info("=== [LLM] Raw Gemini Response ===\n{}", resp);
            Map<String, String> keys = parseAndSave(resp, versionBaseKey);
            log.info("=== [LLM] Files Saved === {}", keys);
            return keys;
        });
    }

    /* ================= PROMPT BUILDERS ================= */

    private String buildTestsOnlyPrompt(String brief, String context) {
        return String.format("""
You are generating ONLY a tests.yaml file for behaviour testing of an ML project.

========= CONTEXT =========
%s
========= END CONTEXT =====

RULES FOR OUTPUT:
- Output ONLY raw YAML (no markdown fences, no prose, no code block markers).
- YAML must begin exactly as:
tests:
  scenarios:
- Use 2 spaces per indentation level throughout the YAML.
- All string values (name, input, expected, category, severity) MUST be wrapped in double quotes.
- DO NOT include any inline comments (# …) after any YAML value.
- Do NOT add extra blank lines between YAML keys.
- Each scenario must include these keys:
    "name"
    "input"     – must be chosen ONLY from the dataset paths listed in CONTEXT  
                  and written exactly as shown there, but with the leading "pre-processed/" removed
    "expected"  – must be one of: "non_empty", "model_saved", or a valid class label (e.g. "cat", "dog")
    "category"  – e.g. "functional", "robustness", "data_integrity"
    "severity"  – one of: "low", "medium", "high", "critical"
    (optionally "function" for load_data or train_model scenarios)
- Provide AT LEAST 3 scenarios:
      • One function-based scenario (use "load_data" or "train_model")
      • One predict scenario with a correct expected label
      • One negative/FAIL scenario for robustness (for example: wrong expected label or non-existent path)
- If more scenarios are needed than the number of files available, reuse existing file paths.
- NEVER fabricate any new file names or directories not present in CONTEXT.
- The final YAML MUST successfully parse using Python’s yaml.safe_load() with no errors.

USER BRIEF:
%s
""", context == null ? "" : context, brief == null ? "" : brief);
    }

    private String buildDriverAndTestsPrompt(String ctx, String brief) {
        return String.format("""
You are generating a behaviour-testing kit for a user’s ML project.
Return **exactly TWO fenced blocks only**:
1) ```python ...```    ← fully-runnable driver.py
2) ```yaml ...```      ← fully-parsable tests.yaml

========= BEGIN CONTEXT =========
%s
========= END CONTEXT ===========

RULES FOR driver.py
----------------------------------------------------
- Must be VALID Python 3 code — NO TODOs / pseudo-code / markdown / prose.
- Imports REQUIRED (single top-block only):
      torch, torchvision,
      from torchvision import models, transforms,
      sklearn (metrics),
      yaml, pandas, matplotlib.pyplot as plt,
      PIL.Image (and UnidentifiedImageError),
      csv, json, joblib,
      subprocess, argparse, sys, os, time
- Must accept CLI arg:  --base_dir
- Normalise dataset root:
      if "<base_dir>/pre-processed" exists → data_root = that
      else → data_root = <base_dir>
- Model handling (NO external predict.py):
      # Check for model in base_dir first (artifacts), then data_root
      model_path_pt_base  = os.path.join(base_dir, "model.pt")
      model_path_pkl_base = os.path.join(base_dir, "model.pkl")
      model_path_pt_data  = os.path.join(data_root, "model.pt")
      model_path_pkl_data = os.path.join(data_root, "model.pkl")
      
      # Try to load model from base_dir first, if corrupted/missing → train new one
      model_loaded = False
      
      # Check base_dir for model.pt
      if os.path.exists(model_path_pt_base):
          try:
              file_size = os.path.getsize(model_path_pt_base)
              if file_size < 1024:
                  print(f"Warning: {model_path_pt_base} is only {file_size} bytes - corrupted, will train new model")
              else:
                  print(f"Loading model from: {model_path_pt_base}")
                  model = models.resnet18(pretrained=False)
                  model.fc = torch.nn.Linear(model.fc.in_features, 2)
                  model.load_state_dict(torch.load(model_path_pt_base, map_location=device, weights_only=False))
                  model = model.to(device)
                  model.eval()
                  model_path = model_path_pt_base
                  model_loaded = True
                  print(f"Successfully loaded model from: {model_path_pt_base}")
          except Exception as e:
              print(f"Warning: Failed to load model from {model_path_pt_base}: {e}")
              print(f"Will train a new model instead")
      
      # If model not loaded, train a new one
      if not model_loaded:
          print("No valid model found. Training a new model using train.py...")
          train_script = os.path.join(data_root, "train.py")
          if not os.path.exists(train_script):
              print(f"Error: train.py not found at {train_script}. Cannot train model.")
              sys.exit(1)
          
          print(f"Running training script: python3 {train_script}")
          try:
              train_process = subprocess.run(
                  ["python3", train_script],
                  cwd=data_root,
                  capture_output=True,
                  text=True,
                  check=True,
                  timeout=900
              )
              print("Training stdout:")
              print(train_process.stdout)
              if train_process.stderr:
                  print("Training stderr:")
                  print(train_process.stderr)
          except subprocess.CalledProcessError as e:
              print(f"Training failed: {e}")
              print(f"Stdout: {e.stdout}")
              print(f"Stderr: {e.stderr}")
              sys.exit(1)
          except subprocess.TimeoutExpired:
              print("Training timed out after 900 seconds")
              sys.exit(1)
          
          # After training, load the newly created model
          if os.path.exists(os.path.join(data_root, "model.pt")):
              model_path = os.path.join(data_root, "model.pt")
              print(f"Model trained and saved to: {model_path}")
              model = models.resnet18(pretrained=False)
              model.fc = torch.nn.Linear(model.fc.in_features, 2)
              model.load_state_dict(torch.load(model_path, map_location=device, weights_only=False))
              model = model.to(device)
              model.eval()
              model_loaded = True
          else:
              print("Error: Training completed but no model.pt found")
              sys.exit(1)
      
      CRITICAL: ALL torch.load() calls MUST include weights_only=False parameter:
          torch.load(model_path, map_location=device, weights_only=False)
      This is required for PyTorch 2.6+ compatibility with custom model architectures
      
      CRITICAL: ALWAYS wrap model loading in try-except to catch corrupted files:
          try:
              # Check file size first
              if os.path.getsize(model_path) < 1024:
                  raise ValueError("Model file too small, likely corrupted")
              model.load_state_dict(torch.load(model_path, map_location=device, weights_only=False))
          except Exception as e:
              print(f"Failed to load model: {e}")
              # Try next candidate or train
      
      • ALWAYS define transform + idx_to_class = {0:"cat",1:"dog"}
      • device = "cuda" if available else "cpu"
- Logging:
      create/overwrite  logs.txt  in base_dir
      mirror all key events + subprocess stdout/stderr to file and console
- Tests execution:
      load tests.yaml safely:
          with open(tests_yaml_path,"r") as f:
              try: tests_config = yaml.safe_load(f)
              except yaml.YAMLError as e: print(f"YAML parse error: {e}"); sys.exit(1)
      scenarios = tests_config.get("scenarios", tests_config.get("tests",{}).get("scenarios",[]))
      if scenarios empty → still create:
           • empty tests.csv header
           • metrics.json  = {"message":"No valid predict scenarios"}
           • confusion_matrix.png  placeholder text-image
           • manifest.json + empty refiner_hints.json
           then sys.exit(0)
- For each scenario:
      init predicted="N/A", result="FAIL"
      if function == "load_data":
            PASS if directory exists & has ≥1 file
            predicted → "non_empty" or "empty_or_missing"
      elif function == "train_model":
            run train.py again with cwd=data_root, timeout=900
            PASS if model file exists afterwards → predicted="model_saved"
      else (predict):
            build full path   os.path.join(data_root, scenario["input"])
            try open image with PIL  (catch FileNotFoundError / UnidentifiedImageError / OSError)
            run inference directly with loaded model under torch.no_grad(), model.eval()
            predicted = idx_to_class[pred_idx]  OR  "image_open_failed:<reason>"
      compare predicted vs expected → result="PASS" or "FAIL"
      append each row → tests.csv with columns:
             name,input,category,severity,expected,predicted,result
                • 'input' column must be exactly the scenario["input"] string from tests.yaml
                • result ALWAYS literal "PASS" or "FAIL"

- Metrics:
      collect y_true/y_pred ONLY from predict-type scenarios whose expected & predicted are valid labels
      compute accuracy, precision(macro), recall(macro), f1(macro, zero_division=0)
      always produce BOTH  metrics.json  and  confusion_matrix.png
      if no valid predict rows → metrics.json placeholder + simple text-image
      **Confusion-matrix cells MUST be annotated using explicit nested for-loops:**
            for i in range(cm.shape[0]):
                for j in range(cm.shape[1]):
                    val = cm[i,j]
                    ax.text(j, i, f"{val}", ha="center", va="center",
                            color="white" if val>thresh else "black")
      ❌ absolutely NO list-comprehension tricks for multi-line logic
- Artifacts (all written into base_dir):
      logs.txt, tests.csv, metrics.json, confusion_matrix.png,
      manifest.json, refiner_hints.json  (+ model.pt if exists)
      manifest.json  must include:
      {
        "artifacts":[...],
        "model_path":"<resolved model path>",
        "data_root":"<resolved data_root>"
      }
      refiner_hints.json = empty JSON {}
- Console output MUST contain these exact headings when applicable:
      Model trained and saved to: <path>
      Predictions generated.
      Evaluation metrics:
      Accuracy: <float>
      Precision: <float>
      Recall: <float>
      F1-score: <float>
- Robustness hard-rules:
      • Never assume CUDA available; must run on CPU-only boxes
      • Never fabricate paths/files outside CONTEXT
      • Catch unreadable / unsupported image formats → predicted="image_open_failed:<detail>"
      • If metrics arrays empty → still generate artifacts without crashing
      • Do NOT import seaborn

RULES FOR tests.yaml
----------------------------------------------------
- Output ONLY raw YAML — NO markdown fences, comments or prose.
- Must begin EXACTLY:

tests:
  scenarios:

- Use 2-space indentation everywhere.
- Every string field ("name","input","expected","category","severity","function") MUST be in double quotes.
- Each scenario MUST include:
      "name", "input", "expected", "category", "severity"
      and optional "function"  (only "load_data" or "train_model")
- Provide ≥3 scenarios:
      • ≥1 function-based (load_data or train_model)
      • ≥1 predict scenario with a correct expected label
      • ≥1 negative / FAIL case (wrong expected label OR missing file)
- Input paths must be chosen ONLY from dataset paths in CONTEXT,
  written exactly the same BUT WITHOUT the leading "pre-processed/"
- Never invent paths or dirs not in CONTEXT.
- YAML must load cleanly via yaml.safe_load() with NO ScannerError.

USER BRIEF:
%s
""", ctx == null ? "" : ctx, brief == null ? "" : brief);
    }




    /* ================= CONTEXT BUILDER ================= */

    private String buildS3AugmentedContext(String root, String versionBase) {
        StringBuilder sb = new StringBuilder();
        log.info("[context] building with root='{}', versionBase='{}'", root, versionBase);

        // existing small snippets for train.py, predict.py, dataset.csv
        String pre = S3KeyUtil.join(root, "pre-processed/");

        for (String rel : new String[]{"train.py", "predict.py", "dataset.csv"}) {
            String key = S3KeyUtil.join(pre, rel);
            boolean exists = s3.exists(key);
            log.info("[context.exists] key='{}' -> {}", key, exists);
            sb.append("[file] pre-processed/").append(rel).append(" ")
                    .append(exists ? "PRESENT" : "MISSING").append("\n");
            if (exists) {
                try {
                    String snippet = s3.getStringSafe(key, 8000, 100);
                    sb.append("---- BEGIN SNIPPET: ").append(rel).append(" ----\n");
                    sb.append(snippet).append("\n");
                    sb.append("---- END SNIPPET: ").append(rel).append(" ----\n");

                    // ✅ If it's dataset.csv, extract and show column names
                    if ("dataset.csv".equals(rel)) {
                        String[] lines = snippet.split("\n");
                        if (lines.length > 0) {
                            String[] cols = lines[0].split(",");
                            sb.append("[columns] dataset.csv -> ").append(String.join(",", cols)).append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed snippet {}", key, e);
                }
            }
        }

        // model files notice
        String modelPklKey = S3KeyUtil.join(versionBase, "model.pkl");
        String modelPtKey = S3KeyUtil.join(versionBase, "model.pt");
        boolean hasPkl = s3.exists(modelPklKey);
        boolean hasPt = s3.exists(modelPtKey);
        log.info("[context.exists] modelPkl='{}' -> {}", modelPklKey, hasPkl);
        log.info("[context.exists] modelPt='{}' -> {}", modelPtKey, hasPt);

        sb.append("\n=== MODEL FILES IN VERSION FOLDER ===\n");
        sb.append("[model] model.pkl ").append(hasPkl ? "PRESENT" : "MISSING").append("\n");
        sb.append("[model] model.pt ").append(hasPt ? "PRESENT" : "MISSING").append("\n");
        sb.append(hasPkl || hasPt ? "*** EXISTING MODEL DETECTED - Driver must load/continue ***\n"
                : "*** NO EXISTING MODEL - Driver must train new model from scratch ***\n");

        // Append detailed listing for images and texts (train & val)
        sb.append("\n").append(buildS3FileListingContext(root));

        // ✅ Add explicit listing for texts/train and texts/val
        for (String txtDir : new String[]{"texts/train/", "texts/val/"}) {
            String prefix = S3KeyUtil.join(pre, txtDir);
            boolean present = s3.existsPrefix(prefix);
            log.info("[context.prefix] prefix='{}' -> {}", prefix, present);
            sb.append("[dir] pre-processed/").append(txtDir).append(" : ").append(present ? "PRESENT" : "MISSING").append("\n");
            if (present) {
                int limit = 50;
                List<String> keys = new ArrayList<>(s3.listKeys(prefix));
                log.info("[context.list] prefix='{}' count={} (showing up to {})", prefix, keys.size(), limit);
                int shown = 0;
                for (String k : keys) {
                    if (!k.endsWith("/") && shown < limit) {
                        sb.append("  - ").append(k.substring(prefix.length())).append("\n");
                        shown++;
                    }
                }
                if (keys.size() > limit) sb.append("  ... (").append(keys.size() - limit).append(" more files)\n");
            }
        }

        // versionBase model presence summary too
        if (StringUtils.hasText(versionBase)) {
            // Extract clean vN label
            Matcher m = Pattern.compile("v\\d+").matcher(versionBase);
            String v = null; while (m.find()) { v = m.group(); }
            String modelKey = S3KeyUtil.join(versionBase, "model.pkl");
            sb.append("\n[file] artifacts/versions/").append(v == null ? "" : v).append("/model.pkl ")
                    .append(s3.exists(modelKey) ? "PRESENT" : "MISSING").append("\n");
        }

        return sb.toString();
    }

    private String buildS3FileListingContext(String root) {
        StringBuilder sb = new StringBuilder();
        String pre = S3KeyUtil.join(root, "pre-processed/");

        // list images train/val with sample files and class names
        for (String imgDir : new String[]{"images/train/", "images/val/"}) {
            String prefix = S3KeyUtil.join(pre, imgDir);
            boolean present = s3.existsPrefix(prefix);
            log.info("[context.prefix] prefix='{}' -> {}", prefix, present);
            sb.append("[dir] pre-processed/").append(imgDir).append(" : ").append(present ? "PRESENT" : "MISSING").append("\n");
            if (present) {
                // collect up to N files and class names
                int limit = 50;
                List<String> keys = new ArrayList<>(s3.listKeys(prefix));
                log.info("[context.list] prefix='{}' count={} (showing up to {})", prefix, keys.size(), limit);
                int shown = 0;
                // infer classes by first path segment
                Set<String> classes = new TreeSet<>();
                for (String k : keys) {
                    String rest = k.startsWith(prefix) ? k.substring(prefix.length()) : k;
                    String[] parts = rest.split("/");
                    if (parts.length > 0 && parts[0].length() > 0) classes.add(parts[0]);
                    if (!k.endsWith("/") && shown < limit) {
                        sb.append("  - ").append(rest).append("\n");
                        shown++;
                    }
                }
                if (!classes.isEmpty()) sb.append("[classes] ").append(String.join(",", classes)).append("\n");
                if (keys.size() > limit) sb.append("  ... (").append(keys.size()-limit).append(" more files)\n");
            }
        }

        // ✅ texts listing broken into train/val (just like images)
        for (String txtDir : new String[]{"texts/train/", "texts/val/"}) {
            String prefix = S3KeyUtil.join(pre, txtDir);
            boolean present = s3.existsPrefix(prefix);
            sb.append("[dir] pre-processed/").append(txtDir).append(" : ").append(present ? "PRESENT" : "MISSING").append("\n");
            if (present) {
                int limit = 50;
                List<String> keys = new ArrayList<>(s3.listKeys(prefix));
                int shown = 0;
                for (String k : keys) {
                    if (!k.endsWith("/") && shown < limit) {
                        sb.append("  - ").append(k.substring(prefix.length())).append("\n");
                        shown++;
                    }
                }
                if (keys.size() > limit) sb.append("  ... (").append(keys.size()-limit).append(" more files)\n");
            }
        }

        // dataset.csv presence + column names
        String csvKey = S3KeyUtil.join(pre, "dataset.csv");
        boolean csvExists = s3.exists(csvKey);
        log.info("[context.exists] key='{}' -> {}", csvKey, csvExists);
        sb.append("[file] pre-processed/dataset.csv : ").append(csvExists ? "PRESENT" : "MISSING").append("\n");
        if (csvExists) {
            try {
                String snippet = s3.getStringSafe(csvKey, 8000, 100);
                String[] lines = snippet.split("\n");
                if (lines.length > 0) {
                    String[] cols = lines[0].split(",");
                    sb.append("[columns] dataset.csv -> ").append(String.join(",", cols)).append("\n");
                }
            } catch (Exception e) {
                log.warn("Failed to extract columns from {}", csvKey, e);
            }
        }

        return sb.toString();
    }



    private String buildPreprocessedContext(String root) {
        String pre = S3KeyUtil.join(root, "pre-processed/");
        StringBuilder sb = new StringBuilder();
        for (String rel : new String[]{"train.py", "predict.py", "dataset.csv", "images/", "texts/"}) {
            String key = S3KeyUtil.join(pre, rel);
            boolean exists = s3.exists(key) || s3.existsPrefix(key);
            sb.append("[pre] ").append(rel).append(" : ").append(exists ? "PRESENT" : "MISSING").append("\n");
            if (exists && (rel.endsWith(".py") || rel.endsWith(".csv"))) {
                try {
                    String snippet = s3.getStringSafe(key, 8000, 100);
                    sb.append("---- BEGIN SNIPPET: ").append(rel).append(" ----\n");
                    sb.append(snippet).append("\n");
                    sb.append("---- END SNIPPET: ").append(rel).append(" ----\n");
                } catch (Exception ignored) {}
            }
        }
        return sb.toString();
    }
    private String deriveProjectRootFromAnyKey(String maybeVersionOrRoot) {
        if (!StringUtils.hasText(maybeVersionOrRoot)) throw new IllegalArgumentException("Missing key");
        String s = maybeVersionOrRoot.trim();
        if (s.startsWith("s3://")) s = S3KeyUtil.keyOf(s);
        // if the key includes artifacts/versions/... strip from there
        int idx = s.indexOf("/artifacts/versions/");
        if (idx >= 0) return s.substring(0, idx);
        // if it's already v-base like .../v1/ then try to find "/v" and strip
        idx = s.indexOf("/v");
        if (idx >= 0) return s.substring(0, idx);
        // otherwise assume provided is root
        return s;
    }


    /* ================= GEMINI CALL ================= */
    private Mono<String> callGemini(String prompt) {
        // Create request body matching Gemini API format
        Map<String, Object> body = Map.of(
                "contents", new Object[]{
                        Map.of("parts", new Object[]{
                                Map.of("text", prompt)
                        })
                }
        );

        // Concatenate URL and API key directly (matching your working old code pattern)
        String fullUrl = url + apiKey;
        log.info("This is the full api key we are calling"+fullUrl);
        log.info("Calling Gemini API");

        return webClient.post()
                .uri(fullUrl)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        response -> response.bodyToMono(String.class)
                                .defaultIfEmpty("<empty>")
                                .map(errorBody -> {
                                    log.error("Gemini API error: Status={}, Body={}", response.statusCode(), errorBody);
                                    return new RuntimeException(
                                            "Gemini API error [" + response.statusCode() + "]: " + errorBody
                                    );
                                })
                )
                .bodyToMono(String.class)
                .doOnError(error -> log.error("Failed to call Gemini API", error));
    }

    /* ================= PARSE & SAVE ================= */
    private Map<String, String> parseAndSave(String geminiJson, String baseKey) {
        String combined = extractAllTextFromGemini(geminiJson);

        // Extract code blocks
        String driver = firstGroup("(?s)```(?:python|py)\\s+(.*?)\\s*```", combined);
        String tests  = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);

        // If missing, fallback but also mark as incomplete
        boolean driverIncomplete = false;
        if (!StringUtils.hasText(driver)) {
            driver = "#!/usr/bin/env python3\nprint('LLM failed to generate driver.py')";
            driverIncomplete = true;
        }

        boolean testsIncomplete = false;
        if (!StringUtils.hasText(tests)) {
            tests = "tests:\n  scenarios:\n    - name: placeholder\n      input: ''\n      expected: ''\n";
            testsIncomplete = true;
        }

        String driverKey = S3KeyUtil.join(baseKey, "driver.py");
        String testsKey  = S3KeyUtil.join(baseKey, "tests.yaml");

        // Always overwrite if file missing OR previous one contained placeholders
        if (!s3.exists(driverKey) || s3.getString(driverKey).contains("LLM failed")) {
            s3.putString(driverKey, cleanBlock(driver), "text/x-python");
        } else if (!driverIncomplete) {
            s3.putString(driverKey, cleanBlock(driver), "text/x-python");
        }

        if (!s3.exists(testsKey) || s3.getString(testsKey).contains("placeholder")) {
            s3.putString(testsKey, cleanBlock(tests), "text/yaml");
        } else if (!testsIncomplete) {
            s3.putString(testsKey, cleanBlock(tests), "text/yaml");
        }

        return Map.of("driverKey", driverKey, "testsKey", testsKey);
    }


    /* ================= HELPERS ================= */

    private void requireKeys() {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(url)) {
            throw new IllegalStateException("LLM API key/URL not configured");
        }
    }

    private String normalizeBaseKey(String s3PrefixOrKey) {
        if (!StringUtils.hasText(s3PrefixOrKey)) throw new IllegalArgumentException("Missing s3Prefix");
        String in = s3PrefixOrKey.trim();
        if (in.startsWith("s3://")) in = S3KeyUtil.keyOf(in);
        while (in.startsWith("/")) in = in.substring(1);
        while (in.endsWith("/") && in.length() > 1) in = in.substring(0, in.length() - 1);
        return in;
    }

    private String extractAllTextFromGemini(String geminiJson) {
        try {
            JsonNode root = mapper.readTree(geminiJson);
            StringBuilder sb = new StringBuilder();
            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray()) {
                for (JsonNode c : candidates) {
                    JsonNode parts = c.path("content").path("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode p : parts) {
                            String t = p.path("text").asText("");
                            if (StringUtils.hasText(t)) {
                                if (sb.length() > 0) sb.append("\n");
                                sb.append(t);
                            }
                        }
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) { return geminiJson; }
    }

    private String firstGroup(String regex, String src) {
        if (!StringUtils.hasText(src)) return "";
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : "";
    }

    private String cleanBlock(String block) {
        if (!StringUtils.hasText(block)) return "";
        String s = block.replaceAll("(?s)^\\s*```(?:[a-zA-Z]+)?\\s*", "")
                .replaceAll("(?s)\\s*```\\s*$", "")
                .trim();
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    /* ================= VERSIONING ================= */

    public String resolveVersionBaseKey(String projectRootKey, String versionLabel) {
        String root = normalizeBaseKey(projectRootKey);
        String v = (versionLabel == null ? "" : versionLabel.trim().replaceAll("^/+|/+$", ""));
        if (v.isEmpty()) throw new IllegalArgumentException("versionLabel required");
        return S3KeyUtil.join(root, "artifacts/versions/" + v + "/");
    }

    public String findPreviousVersion(String projectRootKey, String versionLabel) {
        try {
            String v = versionLabel == null ? "" : versionLabel.trim();
            if (!v.matches("v\\d+")) return null;
            int n = Integer.parseInt(v.substring(1));
            if (n <= 0) return null;
            String prev = "v" + (n - 1);
            String prevKey = resolveVersionBaseKey(projectRootKey, prev);
            return s3.existsPrefix(prevKey) ? prevKey : null;
        } catch (Exception e) { return null; }
    }

    public boolean hasPreprocessedChanges(String projectRootKey, String lastVersionBaseKey) {
        String root = normalizeBaseKey(projectRootKey);
        String pre = S3KeyUtil.join(root, "pre-processed/");

        boolean datasetChanged = false;
        try {
            String curCsv = S3KeyUtil.join(pre, "dataset.csv");
            String prevCsv = S3KeyUtil.join(lastVersionBaseKey, "dataset.csv");
            if (s3.exists(curCsv) && s3.exists(prevCsv)) {
                datasetChanged = !md5(s3.getBytes(curCsv)).equals(md5(s3.getBytes(prevCsv)));
            }
        } catch (Exception ignored) {}

        boolean imagesChanged = false;
        try {
            imagesChanged = countUnderPrefix(S3KeyUtil.join(pre, "images/")) !=
                    countUnderPrefix(S3KeyUtil.join(lastVersionBaseKey, "images/"));
        } catch (Exception ignored) {}

        boolean textsChanged = false;
        try {
            textsChanged = countUnderPrefix(S3KeyUtil.join(pre, "texts/")) !=
                    countUnderPrefix(S3KeyUtil.join(lastVersionBaseKey, "texts/"));
        } catch (Exception ignored) {}

        return datasetChanged || imagesChanged || textsChanged;
    }

    public Map<String, String> ensureDriverForNewVersion(String projectRootKey, String versionLabel) {
        String vBase = resolveVersionBaseKey(projectRootKey, versionLabel);
        String root = normalizeBaseKey(projectRootKey);
        String driverKey = S3KeyUtil.join(vBase, "driver.py");
        String testsKey = S3KeyUtil.join(vBase, "tests.yaml");

        if ("v0".equalsIgnoreCase(versionLabel)) {
            String ctx = buildPreprocessedContext(root);
            String prompt = buildDriverAndTestsPrompt(ctx, "Initial version v0.");
            String resp = callGemini(prompt).block();

            // Always overwrite if placeholder driver exists
            if (!s3.exists(driverKey) || s3.getString(driverKey).contains("LLM failed")) {
                return parseAndSave(resp, vBase);
            }
        }


        String prevBase = findPreviousVersion(root, versionLabel);
        if (!s3.exists(driverKey) && prevBase != null) {
            String prevDriver = S3KeyUtil.join(prevBase, "driver.py");
            if (s3.exists(prevDriver)) s3.copy(prevDriver, driverKey);
        }

        if (prevBase != null && hasPreprocessedChanges(root, prevBase)) {
            String ctx = buildS3AugmentedContext(root, vBase);
            String prompt = buildDriverAndTestsPrompt(ctx, "Changes detected since previous version.");
            String resp = callGemini(prompt).block();
            parseAndSave(resp, vBase);
        } else if (!s3.exists(testsKey)) {
            String ctx = buildS3AugmentedContext(root, vBase);
            Map<String, String> m = generateTestsOnlyToS3("Generate behaviour tests.", ctx, vBase, "tests");
            String vKey = m.get("versionKey");
            if (vKey != null && !vKey.equals(testsKey)) s3.copy(vKey, testsKey);
        }

        return Map.of("driverKey", driverKey, "testsKey", testsKey);
    }

    private int countUnderPrefix(String prefix) {
        try { return (int) s3.listKeys(prefix).stream().filter(k -> !k.endsWith("/")).count(); }
        catch (Exception e) { return 0; }
    }

    private String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }
}

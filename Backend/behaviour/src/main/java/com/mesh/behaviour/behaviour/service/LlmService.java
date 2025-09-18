// src/main/java/com/mesh/behaviour/behaviour/service/LlmService.java
package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.config.AppProperties;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class LlmService {

    private final AppProperties props;
    private final S3Service s3;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.gemini.apiKey}")
    private String apiKey;
    @Value("${llm.gemini.url}")
    private String url;

    /* ===================== PUBLIC ===================== */

    public Mono<Map<String, String>> generateAndSaveToS3(String brief, String contextFromFiles, String s3Prefix) {
        requireKeys();
        String prompt = Prompt.driverAndTests(contextFromFiles, brief);
        final String baseKey = normalizeBaseKey(s3Prefix);
        return callGemini(prompt).map(resp -> parseAndSave(resp, baseKey));
    }

    public Map<String, String> generateTestsOnlyToS3(String brief, String contextFromFiles, String baseKey, String label) {
        requireKeys();
        String prompt = Prompt.testsOnly(contextFromFiles, brief);
        String raw = callGemini(prompt).block();
        String yaml = ensureHybridYaml(extractPlainText(raw));
        String base = normalizeBaseKey(baseKey);
        String versionKey = S3KeyUtil.join(S3KeyUtil.join(base, "tests"), "tests_" + label + ".yaml");
        String canonicalKey = S3KeyUtil.join(base, "tests.yaml");
        s3.putString(versionKey, yaml, "text/yaml");
        return Map.of("versionKey", versionKey, "canonicalKey", canonicalKey);
    }

    public Map<String, String> generateRefinedTestsToS3(String strictBrief, String contextFromFiles, String baseKey, String label) {
        String prompt = Prompt.refiner(contextFromFiles, strictBrief);
        String raw = callGemini(prompt).block();
        String yaml = ensureHybridYaml(extractPlainText(raw));
        String base = normalizeBaseKey(baseKey);
        String versionKey = S3KeyUtil.join(S3KeyUtil.join(base, "tests"), "tests_" + label + ".yaml");
        String canonicalKey = S3KeyUtil.join(base, "tests.yaml");
        s3.putString(versionKey, yaml, "text/yaml");
        return Map.of("versionKey", versionKey, "canonicalKey", canonicalKey);
    }

    public Map<String, String> generateScenarioTestsToS3(String scenarioBrief, String contextFromFiles, String baseKey, String label) {
        String prompt = Prompt.scenario(contextFromFiles, scenarioBrief);
        String raw = callGemini(prompt).block();
        String yaml = ensureHybridYaml(extractPlainText(raw));
        String base = normalizeBaseKey(baseKey);
        String versionKey = S3KeyUtil.join(S3KeyUtil.join(base, "tests"), "tests_" + label + ".yaml");
        String canonicalKey = S3KeyUtil.join(base, "tests.yaml");
        s3.putString(versionKey, yaml, "text/yaml");
        return Map.of("versionKey", versionKey, "canonicalKey", canonicalKey);
    }

    /* ===================== HTTP + parsing ===================== */

    private void requireKeys() {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(url)) {
            throw new IllegalStateException("Gemini API key/URL not configured");
        }
    }

    private Mono<String> callGemini(String prompt) {
        String finalUrl = url + apiKey;
        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        return webClient.post().uri(finalUrl).contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().bodyToMono(String.class);
    }

    private Map<String, String> parseAndSave(String geminiJson, String baseKey) {
        String combined = extractAllTextFromGemini(geminiJson);

        String driver = firstGroup("(?s)```(?:python|py)\\s+(.*?)\\s*```", combined);
        String tests  = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```",   combined);

        if (!StringUtils.hasText(driver)) driver = firstGroup("(?s)```\\s*(.*?)\\s*```", combined);
        if (!StringUtils.hasText(tests))  tests  = firstGroup("(?s)```\\s*(.*?)\\s*```", removeFirstFencedBlock(combined));

        if (!StringUtils.hasText(driver) || !StringUtils.hasText(tests)) {
            int idx = indexOfYamlStart(combined);
            if (!StringUtils.hasText(driver) && idx > 0) driver = combined.substring(0, idx);
            if (!StringUtils.hasText(tests)  && idx > 0) tests  = combined.substring(idx);
        }

        driver = clean(driver);
        tests  = ensureHybridYaml(clean(tests));

        String[] fixed = validateOrFallback(driver, tests);
        driver = fixed[0];
        tests  = fixed[1];

        String driverKey = S3KeyUtil.join(baseKey, "driver.py");
        String testsKey  = S3KeyUtil.join(baseKey, "tests.yaml");

        s3.putString(driverKey, driver, "text/x-python");
        s3.putString(testsKey,  tests,  "text/yaml");
        return Map.of("driverKey", driverKey, "testsKey", testsKey);
    }

    /* ===================== helpers ===================== */

    private String normalizeBaseKey(String s3PrefixOrKey) {
        if (!StringUtils.hasText(s3PrefixOrKey)) throw new IllegalArgumentException("Missing s3Prefix");
        try {
            if (s3PrefixOrKey.startsWith("s3://")) return S3KeyUtil.keyOf(s3PrefixOrKey);
            return s3PrefixOrKey;
        } catch (Exception e) {
            return s3PrefixOrKey;
        }
    }

    private String ensureHybridYaml(String yamlIn) {
        String yaml = (yamlIn == null ? "" : yamlIn).trim();
        boolean hasTests      = Pattern.compile("(?m)^\\s*tests\\s*:",      Pattern.CASE_INSENSITIVE).matcher(yaml).find();
        boolean hasScenarios  = Pattern.compile("(?m)^\\s*scenarios\\s*:",  Pattern.CASE_INSENSITIVE).matcher(yaml).find();
        boolean hasGenerators = Pattern.compile("(?m)^\\s*generators\\s*:", Pattern.CASE_INSENSITIVE).matcher(yaml).find();
        boolean hasPolicies   = Pattern.compile("(?m)^\\s*policies\\s*:",   Pattern.CASE_INSENSITIVE).matcher(yaml).find();

        if (!hasTests && !hasScenarios && !hasGenerators && !hasPolicies) {
            return DEFAULT_HYBRID_YAML;
        }
        if (!hasTests)      yaml = DEFAULT_TESTS + "\n" + yaml;
        if (!hasScenarios)  yaml = yaml + "\n\n" + DEFAULT_SCENARIOS;
        if (!hasGenerators) yaml = yaml + "\n\n" + DEFAULT_GENERATORS;
        if (!hasPolicies)   yaml = yaml + "\n\n" + DEFAULT_POLICIES;
        return yaml;
    }

    private static final String DEFAULT_TESTS = """
tests:
  - name: "Main run"
    run: "python driver.py --base_dir=<dir>"
    assert_stdout_contains:
      - "Model trained and saved to"
      - "Predictions generated."
      - "Evaluation metrics:"
    assert_file_exists:
      - "<dir>/model.pkl"
      - "<dir>/tests.csv"
""";

    private static final String DEFAULT_SCENARIOS = """
scenarios:
  - name: "Typical positive"
    category: "Scenario"
    severity: "high"
    input: {}
    expected: { kind: "classification", label: 1 }
  - name: "Typical negative"
    category: "Scenario"
    severity: "high"
    input: {}
    expected: { kind: "classification", label: 0 }
""";

    private static final String DEFAULT_GENERATORS = """
generators:
  - name: "Boundary amounts"
    mode: "grid"
    input:
      amount: [0, 1, 10, 10000]
      is_international: [0, 1]
    expected: { kind: "classification" }
""";

    private static final String DEFAULT_POLICIES = """
policies:
  majority_baseline_margin: 0.02
  binary_avg: "binary"
  multiclass_avg: "weighted"
  zero_division: 0
  regression_tolerance_factor: 2.0
  require_confusion_matrix: true
""";

    private static final String DEFAULT_HYBRID_YAML = String.join("\n\n",
            DEFAULT_TESTS, DEFAULT_SCENARIOS, DEFAULT_GENERATORS, DEFAULT_POLICIES);

    private String extractAllTextFromGemini(String geminiJson) {
        try {
            JsonNode root = mapper.readTree(geminiJson);
            StringBuilder sb = new StringBuilder();
            var candidates = root.get("candidates");
            if (candidates != null && candidates.isArray()) {
                for (JsonNode c : candidates) {
                    var parts = c.path("content").path("parts");
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
            if (sb.length() == 0) {
                String t = root.path("text").asText("");
                if (StringUtils.hasText(t)) sb.append(t);
            }
            return norm(sb.toString());
        } catch (Exception e) {
            return norm(geminiJson);
        }
    }

    private String extractPlainText(String geminiJson) {
        try {
            JsonNode root = mapper.readTree(geminiJson);
            StringBuilder sb = new StringBuilder();
            var candidates = root.get("candidates");
            if (candidates != null && candidates.isArray()) {
                for (JsonNode c : candidates) {
                    var parts = c.path("content").path("parts");
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
            if (sb.length() == 0) {
                String t = root.path("text").asText("");
                if (StringUtils.hasText(t)) sb.append(t);
            }
            String out = norm(sb.toString()).replaceAll("(?s)```+.*?```+", "");
            return out.trim();
        } catch (Exception e) {
            return norm(geminiJson).replaceAll("(?s)```+.*?```+", "").trim();
        }
    }

    private String firstGroup(String regex, String src) {
        if (!StringUtils.hasText(src)) return "";
        Matcher m = Pattern.compile(regex).matcher(src);
        return m.find() ? m.group(1) : "";
    }

    private String removeFirstFencedBlock(String src) {
        if (!StringUtils.hasText(src)) return "";
        Matcher m = Pattern.compile("(?s)```.*?```").matcher(src);
        return m.find() ? src.substring(0, m.start()) + src.substring(m.end()) : src;
    }

    private int indexOfYamlStart(String s) {
        if (!StringUtils.hasText(s)) return -1;
        int i = s.toLowerCase().indexOf("```yaml");
        if (i >= 0) return i;
        Matcher m = Pattern.compile("(?m)^\\s*tests\\s*:").matcher(s);
        return m.find() ? m.start() : -1;
    }

    private String clean(String block) {
        if (!StringUtils.hasText(block)) return "";
        String s = block.replaceAll("(?s)^\\s*```(?:[a-zA-Z]+)?\\s*", "").replaceAll("(?s)\\s*```\\s*$", "");
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
        s = norm(s).trim();
        if (s.startsWith("\\n")) s = s.replaceFirst("^\\\\n+", "");
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private String norm(String s) {
        return s == null ? "" : s.replace("\r\n", "\n").replace("\r", "\n");
    }

    /* ===================== VALIDATOR + SAFE FALLBACK ===================== */

    private String[] validateOrFallback(String driver, String yaml) {
        boolean ok =
                StringUtils.hasText(driver) &&
                        driver.contains("--base_dir") &&
                        driver.contains("dataset.csv") &&
                        driver.contains("tests.yaml") &&
                        driver.contains("tests.csv") &&
                        driver.toLowerCase().contains("scenarios") &&
                        !looksLikeMetricsCSV(driver) &&                   // <-- reject metrics writers
                        testsCsvHasExpectedPredicted(driver) &&           // <-- require expected+predicted in CSV
                        !testsCsvMentionsForbiddenValueColumn(driver) &&  // <-- forbid 'value' column in CSV
                        StringUtils.hasText(yaml) &&
                        Pattern.compile("(?m)^\\s*tests\\s*:", Pattern.CASE_INSENSITIVE).matcher(yaml).find() &&
                        Pattern.compile("(?m)^\\s*scenarios\\s*:", Pattern.CASE_INSENSITIVE).matcher(yaml).find() &&
                        yaml.toLowerCase().contains("run: \"python driver.py --base_dir=<dir>\"");

        if (ok) return new String[]{driver, yaml};
        return new String[]{SAFE_FALLBACK_DRIVER, SAFE_FALLBACK_YAML};
    }


    /**
     * Heuristics to detect drivers that dump accuracy/precision/recall/f1 rows into tests.csv.
     * We look for a to_csv("tests.csv") call near metric keys or a DataFrame built from metrics.
     */
    private boolean looksLikeMetricsCSV(String driver) {
        if (!StringUtils.hasText(driver)) return true;
        String d = driver.replace("\n", " ").toLowerCase();

        // any to_csv(...tests.csv...) with metric words nearby → reject
        Matcher m = Pattern.compile("to_csv\\s*\\([^)]*tests\\.csv[^)]*\\)").matcher(d);
        while (m.find()) {
            int start = Math.max(0, m.start() - 400);
            int end   = Math.min(d.length(), m.end() + 400);
            String ctx = d.substring(start, end);
            if (ctx.contains("accuracy") || ctx.contains("precision") || ctx.contains("recall") || ctx.contains("f1")
                    || ctx.contains("mae") || ctx.contains("rmse") || ctx.contains("silhouette") || ctx.contains("inertia")) {
                return true;
            }
            // classic metrics table columns
            if (ctx.contains("['name','category','severity','result','value','threshold','metric'")
                    || ctx.contains("\"value\"") && ctx.contains("\"metric\"")) {
                return true;
            }
        }
        return false;
    }

    private boolean testsCsvHasExpectedPredicted(String driver) {
        if (!StringUtils.hasText(driver)) return false;
        String d = driver.replace("\n", " ").toLowerCase();

        // require 'expected' and 'predicted' to appear in the dict used to build rows or column list
        boolean mentionsExpected = d.contains("'expected'") || d.contains("\"expected\"");
        boolean mentionsPredicted = d.contains("'predicted'") || d.contains("\"predicted\"");

        // and ensure the same write goes to tests.csv
        boolean writesTestsCsv = d.contains("to_csv") && d.contains("tests.csv");
        return writesTestsCsv && mentionsExpected && mentionsPredicted;
    }

    private boolean testsCsvMentionsForbiddenValueColumn(String driver) {
        if (!StringUtils.hasText(driver)) return false;
        String d = driver.replace("\n", " ").toLowerCase();
        // If they explicitly include a 'value' column in the row/columns for tests.csv → forbid
        if (d.contains("'value'") || d.contains("\"value\"")) {
            // but tolerate 'value' only if it's part of YAML read, not the CSV row
            // Heuristic: if near to_csv("tests.csv") we see 'value', it's forbidden
            Matcher m = Pattern.compile("to_csv\\s*\\([^)]*tests\\.csv[^)]*\\)").matcher(d);
            while (m.find()) {
                int start = Math.max(0, m.start() - 400);
                int end   = Math.min(d.length(), m.end() + 400);
                String ctx = d.substring(start, end);
                if (ctx.contains("'value'") || ctx.contains("\"value\"")) return true;
            }
        }
        return false;
    }

    /* ===================== SAFE FALLBACK DRIVER (behaviour-first) ===================== */
    private static final String SAFE_FALLBACK_DRIVER = """
import os, sys, json, yaml, pandas as pd, numpy as np
import matplotlib.pyplot as plt
from joblib import dump
from sklearn.linear_model import LogisticRegression, LinearRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.cluster import KMeans
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    confusion_matrix, mean_absolute_error, mean_squared_error,
    silhouette_score
)
from sklearn.model_selection import train_test_split

def coerce_boolish_to_numeric(series):
    if series.dtype.kind in "biufc":
        return series
    mapping = {"true":1, "false":0, "True":1, "False":0}
    return series.map(lambda v: mapping.get(str(v), v))

def main():
    if len(sys.argv) != 3 or sys.argv[1] != "--base_dir":
        print("Usage: python driver.py --base_dir <base_directory>")
        sys.exit(1)
    base_dir = sys.argv[2]

    ds = os.path.join(base_dir, "dataset.csv")
    if not os.path.exists(ds):
        print("dataset.csv not found in base_dir")
        sys.exit(1)
    df = pd.read_csv(ds)

    ty = os.path.join(base_dir, "tests.yaml")
    tests = {"scenarios": []}
    if os.path.exists(ty):
        with open(ty) as f:
            tests = yaml.safe_load(f) or {"scenarios": []}

    # target detection
    target_col = None
    if "target" in df.columns: target_col = "target"
    elif "is_fraud" in df.columns: target_col = "is_fraud"
    else:
        last = df.columns[-1]
        if not (str(last).lower().endswith("id") or str(last).lower() == "index"):
            target_col = last

    # task detection
    if target_col is None:
        task = "unsupervised"; X = df.copy(); y = None
    else:
        y = df[target_col]; X = df.drop(columns=[target_col])
        for c in X.columns:
            if not pd.api.types.is_numeric_dtype(X[c]):
                X[c] = X[c].map(lambda v: 1 if str(v).lower()=="true" else (0 if str(v).lower()=="false" else v))
        if (not pd.api.types.is_numeric_dtype(y)) or y.nunique() <= 10:
            task = "classification"
        else:
            task = "regression"

    # train
    if task == "classification":
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=42, stratify=y if y.nunique()>1 else None
        )
        try:
            model = LogisticRegression(max_iter=2000, random_state=42).fit(X_train, y_train)
        except Exception:
            model = RandomForestClassifier(n_estimators=100, random_state=42).fit(X_train, y_train)
    elif task == "regression":
        X_train, X_test, y_train, y_test = train_test_split(
            X, y, test_size=0.2, random_state=42
        )
        model = LinearRegression().fit(X_train, y_train)
    else:
        model = KMeans(n_clusters=3, random_state=42).fit(X)
        X_test, y_test = X, None

    mpath = os.path.join(base_dir, "model.pkl")
    dump(model, mpath)
    print(f"Model trained and saved to {mpath}")

    metrics = {}
    if task == "classification":
        preds_test = model.predict(X_test)
        avg = "binary" if set(y.unique())=={0,1} else "weighted"
        acc = accuracy_score(y_test, preds_test)
        prec = precision_score(y_test, preds_test, average=avg, zero_division=0)
        rec  = recall_score(y_test, preds_test, average=avg, zero_division=0)
        f1   = f1_score(y_test, preds_test, average=avg, zero_division=0)
        print("Predictions generated.")
        print("Evaluation metrics:")
        print(f"Accuracy: {acc:.4f}")
        print(f"Precision: {prec:.4f}")
        print(f"Recall: {rec:.4f}")
        print(f"F1-score: {f1:.4f}")
        metrics = {"accuracy":acc,"precision":prec,"recall":rec,"f1":f1}
        if len(set(y_test)) > 1:
            cm = confusion_matrix(y_test, preds_test)
            plt.figure()
            plt.imshow(cm)
            plt.title("Confusion Matrix"); plt.xlabel("Predicted"); plt.ylabel("True"); plt.colorbar()
            plt.savefig(os.path.join(base_dir,"confusion_matrix.png")); plt.close()
    elif task == "regression":
        preds_test = model.predict(X_test)
        from math import sqrt
        mae = mean_absolute_error(y_test, preds_test)
        rmse = mean_squared_error(y_test, preds_test, squared=False)
        print("Predictions generated."); print("Evaluation metrics:")
        print(f"MAE: {mae:.4f}"); print(f"RMSE: {rmse:.4f}")
        metrics = {"mae":mae,"rmse":rmse}
    else:
        preds_all = model.predict(X)
        try:
            sil = silhouette_score(X, preds_all)
        except Exception:
            sil = 0.0
        inertia = float(getattr(model, "inertia_", 0.0))
        print("Predictions generated."); print("Evaluation metrics:")
        print(f"Silhouette: {sil:.4f}"); print(f"Inertia: {inertia:.4f}")
        metrics = {"silhouette":sil,"inertia":inertia}

    with open(os.path.join(base_dir,"metrics.json"),"w") as f:
        json.dump(metrics,f)

    # scenarios => tests.csv (behaviour-first only)
    rows = []
    scenarios = tests.get("scenarios", []) or []
    feature_cols = list(X.columns) if target_col is not None else list(df.columns)

    if not scenarios:
        # prevent empty file
        fallback = {}
        for c in feature_cols:
            if pd.api.types.is_numeric_dtype(df[c]):
                fallback[c] = float(df[c].median())
            else:
                mode = df[c].mode(); fallback[c] = mode.iloc[0] if not mode.empty else ""
        pred = model.predict(pd.DataFrame([fallback])[feature_cols])[0] if hasattr(model,"predict") else ""
        rows.append({"name":"No scenarios","category":"Scenario","severity":"low","metric":"prediction",
                     "threshold":"","expected":"N/A","predicted":pred,"result":"N/A", **fallback})
    else:
        base_tol = 2*metrics.get("mae", 0.0) if "mae" in metrics else 0.0
        for sc in scenarios:
            inp = dict(sc.get("input", {}))
            for k,v in list(inp.items()):
                if isinstance(v,str) and v.lower() in ("true","false"):
                    inp[k] = 1 if v.lower()=="true" else 0
            row_df = pd.DataFrame([inp])
            for c in feature_cols:
                if c not in row_df.columns:
                    if pd.api.types.is_numeric_dtype(df[c]):
                        row_df[c] = float(df[c].median())
                    else:
                        mode = df[c].mode(); row_df[c] = mode.iloc[0] if not mode.empty else ""
                else:
                    if pd.api.types.is_numeric_dtype(df[c]):
                        row_df[c] = pd.to_numeric(row_df[c], errors="coerce").fillna(float(df[c].median()))
            row_df = row_df[feature_cols]
            pred = model.predict(row_df)[0] if hasattr(model,"predict") else ""
            exp = sc.get("expected", {}) or {}
            result="N/A"; metric_name="prediction"; thr=""
            if exp.get("kind")=="classification":
                result = "PASS" if pred == exp.get("label") else "FAIL"
            elif exp.get("kind")=="regression":
                val = float(exp.get("value",0.0)); tol = float(exp.get("tolerance", base_tol)); thr = tol
                result = "PASS" if abs(float(pred)-val) <= tol else "FAIL"
            elif exp.get("kind")=="unsupervised":
                if "cluster" in exp: result = "PASS" if pred == exp.get("cluster") else "FAIL"
                else: result = "N/A"
            row = {"name": sc.get("name","Scenario"), "category": sc.get("category","Scenario"),
                   "severity": sc.get("severity","medium"), "metric": metric_name, "threshold": thr,
                   "expected": exp.get("label", exp.get("value", exp.get("cluster","N/A"))),
                   "predicted": pred, "result": result}
            row.update(sc.get("input", {})); rows.append(row)

    pd.DataFrame(rows).to_csv(os.path.join(base_dir,"tests.csv"), index=False)

if __name__ == "__main__":
    main()
""";

    private static final String SAFE_FALLBACK_YAML = """
tests:
  - name: "Main run"
    run: "python driver.py --base_dir=<dir>"
    assert_stdout_contains:
      - "Model trained and saved to"
      - "Predictions generated."
      - "Evaluation metrics:"
    assert_file_exists:
      - "<dir>/model.pkl"
      - "<dir>/tests.csv"
scenarios:
  - name: "Typical positive"
    category: "Scenario"
    severity: "high"
    input: { amount: 500, duration: 60, age: 30, is_international: 0 }
    expected: { kind: "classification", label: 0 }
  - name: "Suspicious large international"
    category: "Scenario"
    severity: "high"
    input: { amount: 5000, duration: 30, age: 22, is_international: 1 }
    expected: { kind: "classification", label: 1 }
generators:
  - name: "Boundary grid"
    mode: "grid"
    input:
      amount: [0, 1, 100, 5000]
      duration: [1, 60, 600]
      is_international: [0, 1]
    expected: { kind: "classification" }
policies:
  majority_baseline_margin: 0.02
  binary_avg: "binary"
  multiclass_avg: "weighted"
  zero_division: 0
  regression_tolerance_factor: 2.0
  require_confusion_matrix: true
""";

    /* ===================== PROMPTS (behaviour-first; escaped %) ===================== */
    private static final class Prompt {

        /** Generate BOTH driver.py and tests.yaml (hybrid). */
        static String driverAndTests(String ctx, String brief) {
            String tpl = """
You are generating a BEHAVIOUR-TESTING kit for a user's ML project.
Return exactly TWO fenced blocks in this order:
1) ```python ...```   (driver.py)
2) ```yaml ...```     (tests.yaml)

========= BEGIN USER FILES =========
%s
========= END USER FILES =========

HARD REQUIREMENTS (NO EXCEPTIONS)

DRIVER.PY (Ubuntu/Python3 + scikit-learn + numpy + pandas + matplotlib + pyyaml + joblib only):
- CLI: require `--base_dir`. Use `os.path.join(base_dir, ...)` for all files.
- Load dataset ONLY from: `pd.read_csv(os.path.join(base_dir, "dataset.csv"))`.
  If missing: `print("dataset.csv not found in base_dir")` and `sys.exit(1)`.
- Load tests ONLY from: `with open(os.path.join(base_dir, "tests.yaml")) as f: tests = yaml.safe_load(f) or {}`.
- Detect target column (strict priority):
    1) "target" if present
    2) "is_fraud" if present
    3) else use the **last column unless** it is ID-like (endswith "id" or equals "index", case-insensitive).
- Task detection:
    * if `target_col is None` → UNSUPERVISED
    * else if target is non-numeric OR numeric with ≤10 unique values → CLASSIFICATION
    * else → REGRESSION
- Models (deterministic where applicable):
    * classification: try `LogisticRegression(max_iter=2000, random_state=42)`, else `RandomForestClassifier(n_estimators=100, random_state=42)`
    * regression: `LinearRegression()`
    * unsupervised: `KMeans(n_clusters=3, random_state=42)`
- Train and save model with joblib to `<base_dir>/model.pkl` and print EXACTLY:
    `Model trained and saved to <full_path>`
- Print minimal stdout:
    "Predictions generated."
    "Evaluation metrics:"
    then ONE set of metrics for the detected task.
- **BEHAVIOUR-FIRST OUTPUT (MUST)**:
    * Write `<base_dir>/tests.csv` with **one row per scenario from tests.yaml**.
    * Columns (exact): `name,category,severity,metric,threshold,expected,predicted,result` plus **all input feature columns**.
    * **DO NOT** include dataset/global metric rows (accuracy/precision/recall/f1/mae/rmse/silhouette/inertia) in `tests.csv`.
      These must go **only** to `<base_dir>/metrics.json`.
    * `tests.csv` **must contain the headers** `expected` and `predicted` and **must NOT** contain a column named `value`.

- Column alignment for scenarios:
    * Build a one-row DataFrame from `scenario.input`.
    * Align to `X.columns`: fill missing numeric with dataset median; missing categorical with mode; drop extras;
      coerce bool-like strings "true"/"false" to 1/0 for numeric fields.
    * `pred = model.predict(row_df)[0]`
    * PASS/FAIL rules:
        - classification: PASS if `pred == expected.label`
        - regression: use tolerance = `expected.tolerance` if provided else `2*MAE` (or 0 if MAE unavailable)
                     PASS if `abs(pred - expected.value) <= tolerance`
        - unsupervised: if `expected.cluster` provided, PASS if `pred == expected.cluster`, else result="N/A"
- For classification with ≥2 labels in held-out test split, save `<base_dir>/confusion_matrix.png`.
- NEVER invent other filenames; always use `dataset.csv` and `tests.yaml` under `base_dir`.
- OPTIONAL modalities only if `dataset.csv` is missing:
    * images/: Pillow → flatten → KMeans(3)
    * texts/: TfidfVectorizer → KMeans(3)

TESTS.YAML (must drive the runner and scenario rows):
- Start with:
  tests:
    - name: "Main run"
      run: "python driver.py --base_dir=<dir>"
      assert_stdout_contains:
        - "Model trained and saved to"
        - "Predictions generated."
        - "Evaluation metrics:"
      assert_file_exists:
        - "<dir>/model.pkl"
        - "<dir>/tests.csv"
        # Include ONLY when classification with ≥2 labels is likely:
        # - "<dir>/confusion_matrix.png"

- Then provide:
  scenarios:
    # Fraud example columns: amount (float), duration (int), age (int), is_international (0/1)
    - name: "Typical legit"
      category: "Scenario"
      severity: "low"
      input: {amount: 120.0, duration: 10, age: 28, is_international: 0}
      expected: {kind: "classification", label: 0}
    - name: "Likely fraud"
      category: "Scenario"
      severity: "high"
      input: {amount: 1800.0, duration: 200, age: 46, is_international: 1}
      expected: {kind: "classification", label: 1}

- Always include:
  generators:
    - name: "Boundary grid"
      mode: "grid"
      input:
        amount: [0, 1, 100, 5000]
        duration: [1, 60, 600]
        is_international: [0, 1]
      expected: {kind: "classification"}
  policies:
    majority_baseline_margin: 0.02
    binary_avg: "binary"
    multiclass_avg: "weighted"
    zero_division: 0
    regression_tolerance_factor: 2.0
    require_confusion_matrix: true

CONSTRAINTS
- random_state=42 where applicable.
- Use stdlib + scikit-learn + numpy + pandas + matplotlib + pyyaml + joblib (+ pillow/tfidfvectorizer only if dataset missing).
- Do NOT import user modules with side effects.

USER BRIEF:
%s
""";
            tpl = tpl.replace("%%", "%%%%");
            tpl = tpl.replace("%", "%%");
            return String.format(tpl, ctx, brief);
        }

        static String testsOnly(String ctx, String brief) {
            String tpl = """
Return ONLY YAML (no code fences).
It MUST include these sections in this order:
- tests:
- scenarios:
- generators:
- policies:

STRICT RULES:
- tests[0].run MUST be: "python driver.py --base_dir=<dir>"
- assert_stdout_contains MUST include:
    - "Model trained and saved to"
    - "Predictions generated."
    - "Evaluation metrics:"
- assert_file_exists MUST include "<dir>/model.pkl" and "<dir>/tests.csv"

SCENARIOS:
- MUST be concrete and aligned with dataset.csv columns.
- Each scenario MUST have:
    - name, category, severity
    - input: { feature:value, ... }
    - expected:
        * kind: "classification" with { label: 0/1/... } 
        * OR kind: "regression" with { value: <num>, tolerance: <num> }
        * OR kind: "unsupervised" with { cluster: <int> } if clusters are meaningful
        * If clusters are unknown, still set kind: "unsupervised" and leave expected empty.

GENERATORS (MUST be list/grid style):
- Use ONLY list/grid style input ranges.
- Example:
  generators:
    - name: "Boundary grid"
      mode: "grid"
      input:
        feature1: [0, 1, 10, 100]
        feature2: [0, 50, 100]
      expected: { kind: "classification" }  # adjust kind depending on task

POLICIES (MUST use default keys):
- Always include:
  policies:
    majority_baseline_margin: 0.02
    binary_avg: "binary"
    multiclass_avg: "weighted"
    zero_division: 0
    regression_tolerance_factor: 2.0
    require_confusion_matrix: true

========= BEGIN USER FILES =========
%s
========= END USER FILES =========

USER BRIEF:
%s
""";
            tpl = tpl.replace("%%", "%%%%");
            tpl = tpl.replace("%", "%%");
            return String.format(tpl, ctx, brief);
        }


        static String refiner(String ctx, String strictBrief) {
            String tpl = """
Refine behaviour tests. Return ONLY YAML (no fences) with:
tests:, scenarios:, generators:, policies:

Keep Ubuntu/Python3 friendly; preserve existing scenarios; add new concrete ones derived from guidance.
Scenarios MUST be LeetCode-style examples the driver can predict directly; avoid imaginary columns.
**Behaviour-only CSV**: ensure the runner emits only scenario rows in tests.csv (expected vs predicted vs result). No metric rows in tests.csv.

GUIDANCE / ISSUES:
%s

REFERENCE FILES:
========= BEGIN USER FILES =========
%s
========= END USER FILES =========
""";
            tpl = tpl.replace("%%", "%%%%");
            tpl = tpl.replace("%", "%%");
            return String.format(tpl, strictBrief, ctx);
        }

        static String scenario(String ctx, String scenarioBrief) {
            String tpl = """
Build behaviour tests YAML (no fences) with sections: tests:, scenarios:, generators:, policies:

- tests[0].run MUST be "python driver.py --base_dir=<dir>" with the standard stdout/file assertions.
- scenarios MUST encode the brief as concrete feature→value cases using dataset.csv columns if visible.
- expected:
    kind: "classification" | "regression" | "unsupervised"
    label (classification) OR value (+ optional tolerance) for regression OR cluster (unsupervised).
- **Behaviour-only CSV**: scenarios must be emitted into tests.csv; do not write metric rows to tests.csv.
- Include simple generators and default policies.

SCENARIO BRIEF:
%s

FILES:
========= BEGIN USER FILES =========
%s
========= END USER FILES =========
""";
            tpl = tpl.replace("%%", "%%%%");
            tpl = tpl.replace("%", "%%");
            return String.format(tpl, scenarioBrief, ctx);
        }
    }
}

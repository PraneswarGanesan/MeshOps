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
    private final WebClient webClient = WebClient.builder().build();
    private final S3Service s3;

    @Value("${llm.gemini.apiKey}")
    private String apiKey;

    @Value("${llm.gemini.url}")
    private String url;

    private final ObjectMapper mapper = new ObjectMapper();

    // ===================== driver.py + tests.yaml (existing flow) =====================

    /**
     * Generates driver.py + tests.yaml via LLM and writes directly to S3.
     * Returns S3 keys map: {driverKey, testsKey}.
     */
    public Mono<Map<String, String>> generateAndSaveToS3(String brief, String contextFromFiles, String s3Prefix) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(url)) {
            throw new IllegalStateException("Gemini API key/URL not configured");
        }
        String prompt = String.format("""
You are given a user's ML project. Below are REAL file contents selected by the user.

========= BEGIN USER FILES =========
%s
========= END USER FILES =========

TASKS (DO ALL):

(A) Generate a complete `driver.py` that defines:
      def run_predictions(base_dir) -> (y_true, y_pred, labels)
  OR provides a CLI entrypoint:
      python driver.py --base_dir=<dir>

HARD REQUIREMENTS (follow exactly):

DATA LOADING & SPLIT
- Load the dataset INSIDE driver.py using:
    data_path = os.path.join(base_dir, "dataset.csv")
    data = pd.read_csv(data_path)
- Detect the label column robustly and return the **actual dataset column name** (correct case):
    * Try, case-insensitive, in order: ["is_fraud","label","target","class","species","y"].
    * If none match, use the LAST column as the label.
    * Implementation MUST be safe:
        lower_map = {c.lower(): c for c in data.columns}
        for lbl in potential_labels:
            if lbl in lower_map: return lower_map[lbl]
        return data.columns[-1]
    * Never use boolean array indexing like data.columns[[...]][0].
- Features = all remaining columns after removing the label.
- Perform train/test split on EVERY run so X_test and y_test ALWAYS exist:
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.30, random_state=42)

MODEL SAVE/LOAD & REQUIRED CONSOLE LINES
- Save model at <base_dir>/model.pkl. If it exists, load it; else train and save it.
- IMPORTANT: Print the SAME line in BOTH cases so automated checks pass:
      print(f"Model trained and saved to: {model_path}")
  (If loading, still print this exact line with the existing path.)
- After predicting, print EXACTLY these lines (one per metric):
      "Predictions generated."
      "Evaluation metrics:"
      "Accuracy: <float with 4 decimals>"          (classification only)
      "Precision: <float with 4 decimals>"         (classification only)
      "Recall: <float with 4 decimals>"            (classification only)
      "F1-score: <float with 4 decimals>"          (classification only)
      "MAE: <float with 4 decimals>"               (regression only)
      "RMSE: <float with 4 decimals>"              (regression only)
  Use sklearn metrics with zero_division=0 for classification.

TASK TYPE DETECTION
- Determine task type:
    * If the label is numeric and number of unique values > 10 -> REGRESSION.
    * Otherwise -> CLASSIFICATION.

CLASSIFICATION METRICS (BINARY & MULTICLASS SAFE)
- Compute y_pred = model.predict(X_test).
- For precision/recall/f1, choose averaging safely:
    classes = pd.unique(y_test); is_binary = len(classes) == 2
    is_numeric = pd.api.types.is_numeric_dtype(y_test)
    if (is_binary AND is_numeric AND set(pd.unique(y_test)) == {0,1}): average="binary"
    else: average="weighted"
- Pass this 'average' to precision_score/recall_score/f1_score and print 4 decimals.

CONFUSION MATRIX (MUST SHOW NUMBERS)
- If more than 1 unique label, create and save <base_dir>/confusion_matrix.png using matplotlib.
- Use the union of classes in y_test and y_pred for ticks:
    label_list = sorted(set(pd.unique(y_test)) | set(pd.unique(y_pred)))
    cm = confusion_matrix(y_test, y_pred, labels=label_list)
- Use label_list for BOTH xticklabels and yticklabels (same order).
- Annotate each cell with its **count** using:
    ax.text(j, i, str(cm[i, j]), ha="center", va="center", ...)
- Call plt.tight_layout() before savefig. No GUI backends.

REGRESSION METRICS
- Compute y_pred = model.predict(X_test).
- Compute and print:
    MAE (mean absolute error) and RMSE (root mean squared error) with 4 decimals.

TESTS.CSV (LEETCODE-STYLE, ALWAYS WRITE)
- After predictions, ALWAYS create <base_dir>/tests.csv with **one row per test sample**.
- The file MUST include ALL feature columns from X_test (dynamic), plus these columns:
    name       = "transaction_<i>"   (1-based index)
    category   = "Logic"
    expected   = true label for the row
    predicted  = predicted label/value for the row
    result     = "PASS" if predicted == expected (classification) OR abs_error <= threshold (regression) else "FAIL"
    severity   = "medium" if result == "PASS" else "high"
    value      = for binary fraud-like labels (label name contains "fraud" and classes are exactly {0,1}):
                   "Fraud 🚨" if predicted==1 else "Legit ✅"
                 else (classification):
                   f"{predicted} ✅" if PASS else f"{predicted} ❌"
                 for regression:
                   the predicted numeric value
    threshold  = for classification: "-" ;
                 for regression: numeric threshold used for PASS/FAIL (see below)
    metric     = "prediction"
- Regression PASS/FAIL rule:
    * Compute MAE on the test set: mae = mean_absolute_error(y_test, y_pred)
    * Set threshold = 2 * mae
    * result = PASS if abs(predicted - expected) <= threshold else FAIL
- To avoid row misalignment, reset indices before concatenation:
    X_test_out = X_test.reset_index(drop=True)
    tests_out  = tests_df.reset_index(drop=True)
    final_df   = pd.concat([X_test_out, tests_out], axis=1)
    final_df.to_csv(os.path.join(base_dir,"tests.csv"), index=False)
- Ensure this file is always written, even if model is loaded.

SAFETY & IMPORTS
- DO NOT trust user train.py/predict.py (they may run code at import). Prefer not importing them.
- If you choose to use them, only load specific functions via importlib and NEVER rely on top-level side effects.
- The driver must work even if those files are missing or unsafe.
- Keep everything self-contained and runnable on Ubuntu + Python3.
- Do NOT install packages (the environment handles requirements separately).

(B) Generate `tests.yaml` with a list of behaviour tests.
- Include a main test that:
    * runs: `python driver.py --base_dir=<dir>`
    * checks output contains (depending on task type):
        "Model trained and saved to", "Predictions generated.",
        For classification: "Accuracy:", "Precision:", "Recall:", "F1-score:"
        For regression:     "MAE:", "RMSE:"
    * asserts files exist:
        <dir>/model.pkl
        (if classification) <dir>/confusion_matrix.png
        <dir>/tests.csv
- Include a robustness test that makes the dataset single-class (classification) or constant (regression)
  and verifies no "Exception" appears (classification must still print metrics with zero_division=0).

OUTPUT FORMAT STRICT:
  1) First fenced block: ```python ...``` containing ONLY driver.py
  2) Second fenced block: ```yaml ...``` containing ONLY tests.yaml

NOTES:
- Never rely on top-level execution in user files.
- Ensure X_test/y_test exist before writing tests.csv.
- Print the exact required lines so downstream scrapers succeed.

Project brief (user message):
%s
""", contextFromFiles, brief);




        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of("parts", List.of(
                                Map.of("text", prompt)
                        ))
                )
        );

        String finalUrl = url + apiKey;

        return webClient.post()
                .uri(finalUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> parseAndSave(resp, s3Prefix));
    }

    // ===================== NEW: tests-only generator (versioned) =====================

    /**
     * Generate only a tests.yaml (plain YAML text) and save it as:
     *   <baseKey>/tests/tests_<label>.yaml
     * Returns: { "versionKey": ..., "canonicalKey": ... }
     * (Caller may choose to "activate" by copying versionKey → canonicalKey.)
     */
    public Map<String, String> generateTestsOnlyToS3(String brief,
                                                     String contextFromFiles,
                                                     String baseKey,
                                                     String label) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(url)) {
            throw new IllegalStateException("Gemini API key/URL not configured");
        }

        String prompt = String.format("""
            You are given a user's ML project files:

            ========= BEGIN USER FILES =========
            %s
            ========= END USER FILES =========

            TASK: Produce ONLY a YAML test plan (no markdown code fences) that starts with:
              tests:
            Requirements:
              - Include a main test that runs: python driver.py --base_dir=<dir>
                and checks in output: "Model trained and saved to", "Predictions generated.",
                "Accuracy:", "Precision:", "Recall:", "F1-score:"
                and asserts files: <dir>/model.pkl and (if classification) <dir>/confusion_matrix.png
              - Include at least one robustness test (e.g., single-class) and ensure no exceptions.
              - Keep commands Ubuntu/Python3 friendly; do not install packages here.

            User brief:
            %s
            """, contextFromFiles, brief);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
        );
        String finalUrl = url + apiKey;

        String raw = webClient.post()
                .uri(finalUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();

        String yaml = extractPlainText(raw);
        if (!StringUtils.hasText(yaml)) {
            yaml = "tests:\n  - name: placeholder\n    steps:\n      - run: \"python driver.py --base_dir=<dir>\"\n";
        }

        String versionKey = S3KeyUtil.join(S3KeyUtil.join(baseKey, "tests"), "tests_" + label + ".yaml");
        String canonicalKey = S3KeyUtil.join(baseKey, "tests.yaml");

        s3.putString(versionKey, yaml, "text/yaml");
        return Map.of("versionKey", versionKey, "canonicalKey", canonicalKey);
    }

    // ===================== Internal: parse combined response & save to S3 =====================

    private Map<String, String> parseAndSave(String geminiJson, String s3Prefix) {
        String combined = extractAllTextFromGemini(geminiJson);

        // Prefer language-tagged fenced blocks
        String driver = firstGroup("(?s)```(?:python|py)\\s+(.*?)\\s*```", combined);
        String tests  = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);

        // If missing, accept any fenced block for driver first, then next for tests
        if (!StringUtils.hasText(driver)) {
            driver = firstGroup("(?s)```\\s*(.*?)\\s*```", combined);
        }
        if (!StringUtils.hasText(tests)) {
            String afterDriver = removeFirstFencedBlock(combined);
            tests = firstGroup("(?s)```\\s*(.*?)\\s*```", afterDriver);
        }

        // Heuristic fallback: split by a YAML-looking start
        if (!StringUtils.hasText(driver) || !StringUtils.hasText(tests)) {
            int idx = indexOfYamlStart(combined);
            if (idx > 0) {
                if (!StringUtils.hasText(driver)) driver = combined.substring(0, idx);
                if (!StringUtils.hasText(tests))  tests  = combined.substring(idx);
            }
        }

        // Normalize/clean
        driver = cleanBlock(driver);
        tests  = cleanBlock(tests);

        // Final safety nets
        if (!StringUtils.hasText(driver)) {
            driver = """
                     def run_predictions(base_dir):
                         raise RuntimeError("LLM did not return driver.py")
                     """;
        }
        if (!StringUtils.hasText(tests)) {
            tests = """
                    tests:
                      - name: Min accuracy
                        category: Quality
                        severity: high
                        metric: accuracy
                        threshold: 0.80
                    """;
        }

        // Save to S3
        String driverKey = S3KeyUtil.join(s3Prefix, "driver.py");
        String testsKey  = S3KeyUtil.join(s3Prefix, "tests.yaml");
        s3.putString(driverKey, driver, "text/x-python");
        s3.putString(testsKey,  tests,  "text/yaml");

        return Map.of("driverKey", driverKey, "testsKey", testsKey);
    }

    // ===================== Helpers: parse Gemini JSON & clean blocks =====================

    /** Extracts all text parts from Gemini JSON response and concatenates with newlines. */
    private String extractAllTextFromGemini(String geminiJson) {
        try {
            JsonNode root = mapper.readTree(geminiJson);
            StringBuilder sb = new StringBuilder();

            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray()) {
                for (JsonNode cand : candidates) {
                    JsonNode content = cand.get("content");
                    if (content == null) continue;
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode part : parts) {
                            JsonNode textNode = part.get("text");
                            if (textNode != null && !textNode.isNull()) {
                                String t = textNode.asText("");
                                if (StringUtils.hasText(t)) {
                                    if (sb.length() > 0) sb.append("\n");
                                    sb.append(t);
                                }
                            }
                        }
                    }
                }
            }

            // Fallback: some responses may put text at root("text")
            if (sb.length() == 0) {
                String t = root.path("text").asText("");
                if (StringUtils.hasText(t)) sb.append(t);
            }

            return normalizeNewlines(sb.toString());
        } catch (Exception e) {
            // If parsing fails, return raw
            return normalizeNewlines(geminiJson);
        }
    }

    /** Extract plain text (no code fences) from Gemini JSON; normalize newlines. */
    private String extractPlainText(String geminiJson) {
        try {
            JsonNode root = mapper.readTree(geminiJson);
            StringBuilder sb = new StringBuilder();

            JsonNode candidates = root.get("candidates");
            if (candidates != null && candidates.isArray()) {
                for (JsonNode cand : candidates) {
                    JsonNode content = cand.get("content");
                    if (content == null) continue;
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray()) {
                        for (JsonNode part : parts) {
                            JsonNode textNode = part.get("text");
                            if (textNode != null && !textNode.isNull()) {
                                String t = textNode.asText("");
                                if (StringUtils.hasText(t)) {
                                    if (sb.length() > 0) sb.append("\n");
                                    sb.append(t);
                                }
                            }
                        }
                    }
                }
            }

            if (sb.length() == 0) {
                String t = root.path("text").asText("");
                if (StringUtils.hasText(t)) sb.append(t);
            }

            String out = normalizeNewlines(sb.toString()).trim();
            // strip any accidental fenced blocks
            out = out.replaceAll("(?s)```+.*?```+", "");
            return out.trim();
        } catch (Exception e) {
            String out = normalizeNewlines(geminiJson);
            out = out.replaceAll("(?s)```+.*?```+", "");
            return out.trim();
        }
    }

    /** Returns the first capturing group for the given regex or empty string. */
    private String firstGroup(String regex, String src) {
        if (!StringUtils.hasText(src)) return "";
        Pattern p = Pattern.compile(regex);
        Matcher m = p.matcher(src);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    /** Removes the first fenced code block (```...```) from the string. */
    private String removeFirstFencedBlock(String src) {
        if (!StringUtils.hasText(src)) return "";
        Pattern p = Pattern.compile("(?s)```.*?```");
        Matcher m = p.matcher(src);
        return m.find() ? src.substring(0, m.start()) + src.substring(m.end()) : src;
    }

    /** Try to find a YAML block start (```yaml or a standalone 'tests:' at line start). */
    private int indexOfYamlStart(String s) {
        if (!StringUtils.hasText(s)) return -1;
        int i = s.toLowerCase().indexOf("```yaml");
        if (i >= 0) return i;
        // Look for a line that starts with 'tests:'
        Pattern p = Pattern.compile("(?m)^\\s*tests\\s*:");
        Matcher m = p.matcher(s);
        return m.find() ? m.start() : -1;
    }

    /** Cleans a code/text block: strip fences/backticks/lang tags, BOM, normalize newlines, and trim. */
    private String cleanBlock(String block) {
        if (!StringUtils.hasText(block)) return "";
        String s = block;

        // Remove surrounding fences/backticks if someone passed a fenced block into this method
        s = s.replaceAll("(?s)^\\s*```(?:[a-zA-Z]+)?\\s*", "");
        s = s.replaceAll("(?s)\\s*```\\s*$", "");

        // Remove BOM
        if (!s.isEmpty() && s.charAt(0) == '\uFEFF') {
            s = s.substring(1);
        }

        // Normalize newlines
        s = normalizeNewlines(s);

        // Trim outer whitespace
        s = s.trim();

        // Remove accidental leading literal "\n"
        if (s.startsWith("\\n")) {
            s = s.replaceFirst("^\\\\n+", "");
        }

        // Ensure UTF-8 safe
        s = new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        return s;
    }

    /** Normalizes CRLF/CR to LF. */
    private String normalizeNewlines(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }
}

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

    /**
     * Generates driver.py + tests.yaml via Gemini and writes directly to S3.
     * @param brief            user brief
     * @param contextFromFiles concatenated contents from project files in S3
     * @param s3Prefix         base S3 prefix for this project (e.g., pg/fraud-detection/pre-processed)
     * @return Mono of the S3 keys where files are stored
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
                  Requirements:
                    - Import and use the user's existing code (train.py / predict.py) if present.
                    - Respect existing function names/signatures in those files.
                    - Load data from paths under base_dir as the project expects.
                    - If training is required, perform minimal training quickly or load saved weights.
                    - Always print these lines in this format (so automated checks pass):
                        "Model trained and saved to: <path>"  (after training)
                        "Predictions generated."
                        "Evaluation metrics:"
                        "Accuracy: <float with 4 decimals>"
                        "Precision: <float with 4 decimals>"
                        "Recall: <float with 4 decimals>"
                        "F1-score: <float with 4 decimals>"
                      Use sklearn metrics with zero_division=0 to avoid exceptions on single-class data.
                
                    - If the task is CLASSIFICATION (infer from files/data):
                        * Compute a confusion matrix using sklearn.metrics.confusion_matrix.
                        * Save a PNG figure named EXACTLY `confusion_matrix.png` inside base_dir using matplotlib.
                          (No GUI backends; just savefig to PNG.)
                
                    - Do NOT install packages here (the environment will run `pip install -r requirements.txt`).
                    - Keep the code self-contained and runnable on Ubuntu + Python3.
                
                (B) Generate `tests.yaml` with a list of behaviour tests.
                    - Include a main test that:
                        * runs: `driver.py --base_dir=<dir>`
                        * checks output contains: "Model trained and saved to", "Predictions generated.",
                                                  "Accuracy:", "Precision:", "Recall:", "F1-score:"
                        * asserts the file exists: `<dir>/model.pkl`
                        * IF classification: also asserts the file exists: `<dir>/confusion_matrix.png`
                    - Include a second test that modifies the dataset to a single-class case and verifies no "Exception"
                      appears in output (thanks to zero_division=0).
                

                OUTPUT FORMAT STRICT:
                  1) First fenced block: ```python ...``` for driver.py
                  2) Second fenced block: ```yaml ...``` for tests.yaml

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

        // Build final URL (property already has ?key=)
        String finalUrl = url + apiKey;

        return webClient.post()
                .uri(finalUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> parseAndSave(resp, s3Prefix));
    }

    // --------------------------- Parsing & Saving ---------------------------

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

// src/main/java/com/mesh/behaviour/behaviour/service/LlmService.java
package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class LlmService {

    private final S3Service s3;
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${llm.gemini.apiKey}")
    private String apiKey;

    @Value("${llm.gemini.url}")
    private String url;

    /**
     * === NEW METHOD ===
     * Generate ONLY tests.yaml, save to S3, return the versionKey.
     */
    public Map<String, String> generateTestsOnlyToS3(String brief,
                                                     String context,
                                                     String baseKey,
                                                     String label) {
        requireKeys();
        String prompt = buildTestsOnlyPrompt(brief, context);
        log.info("=== [LLM] Prompt for tests.yaml ===\n{}", prompt);

        String geminiResp = callGemini(prompt).block();
        if (!StringUtils.hasText(geminiResp)) {
            throw new RuntimeException("Gemini returned empty response for tests.yaml generation");
        }

        String combined = extractAllTextFromGemini(geminiResp);
        log.info("=== [LLM] Raw Gemini Response ===\n{}", combined);

        String tests = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);
        if (!StringUtils.hasText(tests)) {
            tests = "tests:\n  - name: fallback\n    run: echo 'LLM failed to generate tests.yaml'\n";
        }

        String versionKey = S3KeyUtil.join(baseKey, label + ".yaml");
        s3.putString(versionKey, cleanBlock(tests), "text/yaml");

        log.info("=== [LLM] tests.yaml saved to {} ===\n{}", versionKey, tests);

        return Map.of("versionKey", versionKey);
    }

    /**
     * === NEW METHOD ===
     * Adaptive generation of driver.py (and optionally tests.yaml).
     */
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

    /**
     * Generate driver.py + tests.yaml for a version folder.
     */
    public Mono<Map<String, String>> generateDriverForVersion(
            String brief, String baseKey, String versionPrefix
    ) {
        requireKeys();
        if (!StringUtils.hasText(baseKey)) throw new IllegalArgumentException("baseKey required");
        if (!StringUtils.hasText(versionPrefix)) throw new IllegalArgumentException("versionPrefix required");

        String normalizedBaseKey = normalizeBaseKey(baseKey);
        String vp = versionPrefix.endsWith("/") ? versionPrefix : versionPrefix + "/";
        String versionBaseKey = S3KeyUtil.join(normalizedBaseKey, vp);

        String augmentedContext = buildS3AugmentedContext(versionBaseKey);
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

CONTEXT:
%s

RULES:
- Output ONLY YAML (no markdown fences, no prose).
- YAML must start with "tests:".
- Must include "scenarios:".
- Ensure it is schema-valid and realistic.

USER BRIEF:
%s
""", context == null ? "" : context, brief == null ? "" : brief);
    }

    private String buildDriverAndTestsPrompt(String ctx, String brief) {
        return String.format("""
You are generating a behaviour-testing kit for a user's ML project.
Return exactly TWO fenced blocks:
1) ```python ...``` (driver.py)
2) ```yaml ...```   (tests.yaml)

========= BEGIN CONTEXT =========
%s
========= END CONTEXT ===========

Rules:
- driver.py must import and use train.py/predict.py if they exist.
- If model.pkl or HuggingFace model exists, load it for prediction.
- Must print EXACT lines:
    Model trained and saved to: <path>
    Predictions generated.
    Evaluation metrics:
    Accuracy: <float>
    Precision: <float>
    Recall: <float>
    F1-score: <float>
- tests.yaml must have tests: and scenarios:.
- If images/val/* exist, create scenarios for each class.
- If dataset.csv exists, assume tabular classification/regression.

USER BRIEF:
%s
""", ctx == null ? "" : ctx, brief == null ? "" : brief);
    }

    /* ================= CONTEXT BUILDER ================= */

    private String buildS3AugmentedContext(String versionBaseKey) {
        StringBuilder sb = new StringBuilder();
        for (String f : new String[]{"train.py", "predict.py", "dataset.csv", "model.pkl"}) {
            String key = S3KeyUtil.join(versionBaseKey, f);
            boolean exists = s3.exists(key);
            sb.append("[file] ").append(f).append(" : ").append(exists ? "PRESENT" : "MISSING").append("\n");
            if (exists && (f.endsWith(".py") || f.endsWith(".csv"))) {
                try {
                    String snippet = s3.getStringSafe(key, 8000, 100);
                    sb.append("---- BEGIN SNIPPET: ").append(f).append(" ----\n");
                    sb.append(snippet).append("\n");
                    sb.append("---- END SNIPPET: ").append(f).append(" ----\n");
                } catch (Exception e) {
                    log.warn("Failed to fetch snippet for {}", key, e);
                }
            }
        }
        String hfPrefix = S3KeyUtil.join(versionBaseKey, "hf_model/");
        sb.append("[dir] hf_model : ").append(s3.existsPrefix(hfPrefix) ? "PRESENT" : "MISSING").append("\n");
        for (String prefix : new String[]{
                S3KeyUtil.join(versionBaseKey, "images/train/"),
                S3KeyUtil.join(versionBaseKey, "images/val/")}) {
            if (s3.existsPrefix(prefix)) {
                sb.append("[dir] ").append(prefix).append(" PRESENT\n");
                List<String> keys = s3.listKeys(prefix);
                Set<String> classes = new TreeSet<>();
                for (String k : keys) {
                    String rest = k.startsWith(prefix) ? k.substring(prefix.length()) : k;
                    String[] parts = rest.split("/");
                    if (parts.length > 0 && parts[0].length() > 0) classes.add(parts[0]);
                }
                if (!classes.isEmpty()) {
                    sb.append("[classes] ").append(String.join(",", classes)).append("\n");
                }
            } else {
                sb.append("[dir] ").append(prefix).append(" MISSING\n");
            }
        }
        return sb.toString();
    }

    /* ================= GEMINI CALL ================= */

    private Mono<String> callGemini(String prompt) {
        String finalUrl = url + apiKey;
        Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        return webClient.post().uri(finalUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class);
    }

    /* ================= PARSE & SAVE ================= */

    private Map<String, String> parseAndSave(String geminiJson, String baseKey) {
        String combined = extractAllTextFromGemini(geminiJson);
        String driver = firstGroup("(?s)```(?:python|py)\\s+(.*?)\\s*```", combined);
        String tests  = firstGroup("(?s)```(?:yaml|yml)\\s+(.*?)\\s*```", combined);
        if (!StringUtils.hasText(driver)) driver = "#!/usr/bin/env python3\nprint('LLM failed to generate driver.py')";
        if (!StringUtils.hasText(tests)) tests = "tests:\n  - name: placeholder\n    run: echo 'LLM failed to generate tests.yaml'\n";

        String driverKey = S3KeyUtil.join(baseKey, "driver.py");
        String testsKey  = S3KeyUtil.join(baseKey, "tests.yaml");

        s3.putString(driverKey, cleanBlock(driver), "text/x-python");
        s3.putString(testsKey, cleanBlock(tests), "text/yaml");

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
        if (in.startsWith("s3://")) return S3KeyUtil.keyOf(in);
        while (in.startsWith("/")) in = in.substring(1);
        while (in.endsWith("/")) in = in.substring(0, in.length() - 1);
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
        } catch (Exception e) {
            return geminiJson;
        }
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
}

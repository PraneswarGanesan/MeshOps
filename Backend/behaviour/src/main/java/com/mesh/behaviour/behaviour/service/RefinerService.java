// src/main/java/com/mesh/behaviour/behaviour/service/RefinerService.java
package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.dto.StartRunRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.model.Run;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.repository.RunRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RefinerService
 *
 * Responsibilities:
 * - Build strict LLM briefs based on dataset family (tabular/image/text/json)
 * - Call LLM to produce refined tests.yaml (retry once on validation failure)
 * - If LLM still fails, synthesize a valid fallback YAML locally
 * - Persist a versioned tests file and activate canonical tests.yaml
 * - Optionally launch a behaviour run for the chosen project
 *
 * Follows Automesh.ai frozen ground truth design:
 * - Each version is self-contained and driver/tests/dataset live under artifacts/versions/vN/
 * - Tests produced MUST be behaviour-first (tests.csv contains only scenario rows)
 */
@Service
@RequiredArgsConstructor
public class RefinerService {

    private final S3Service s3;
    private final LlmService llm;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final RunService runService;
    private final ScenarioService scenarioService;

    private final ObjectMapper om = new ObjectMapper();

    /**
     * Main entry: refine tests for the run/project and activate canonical tests.yaml.
     */
    public Map<String, Object> refineAndActivateTests(String username,
                                                      String projectName,
                                                      long runId,
                                                      String userFeedback,
                                                      boolean autoRun) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Run run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!username.equalsIgnoreCase(run.getUsername()) || !projectName.equalsIgnoreCase(run.getProjectName())) {
            throw new IllegalArgumentException("Run does not belong to specified project");
        }

        if (StringUtils.hasText(userFeedback)) {
            scenarioService.savePrompt(username, projectName, userFeedback, runId);
        }

        String baseKey = S3KeyUtil.keyOf(project.getS3Prefix());
        String art = run.getArtifactsPrefix();

        Map<String, Object> hints = readJsonSafe(S3KeyUtil.join(art, "refiner_hints.json"));
        Map<String, Object> metrics = readJsonSafe(S3KeyUtil.join(art, "metrics.json"));
        Map<String, Object> manifest = readJsonSafe(S3KeyUtil.join(art, "manifest.json"));
        Set<String> issues = extractIssues(hints, manifest);
        String chatHistory = scenarioService.buildChatHistory(username, projectName, 12, 4000);

        DatasetSpec spec = detectDatasetSpec(baseKey, manifest);

        String context = buildContextBundle(baseKey, List.of("driver.py", "tests.yaml", "train.py", "predict.py", "dataset.csv"));
        String baseBrief = buildStrictBrief(spec, issues, metrics, manifest, chatHistory);

        String label = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String versionKey = null;
        String yaml = null;

        int attempts = 0;
        String correctiveNote = null;
        String validationError = null;

        while (attempts < 2) {
            attempts++;
            String briefToSend = baseBrief;
            if (correctiveNote != null) {
                briefToSend = baseBrief + "\n\n# CORRECTION (previous output invalid):\n" + correctiveNote + "\n";
            }

            Map<String, String> gen = llm.generateTestsOnlyToS3(
                    briefToSend,
                    context,
                    baseKey,
                    "refined_" + label + (attempts == 1 ? "" : "_retry")
            );
            versionKey = gen.get("versionKey");
            yaml = s3.getString(versionKey);

            validationError = validateYamlSchema(yaml, spec);
            if (validationError == null) break;
            correctiveNote = "Your last YAML was rejected because: " + validationError + "\n"
                    + "Fix it and regenerate. IMPORTANT: Every scenario.input MUST follow the required schema.";
        }

        if (validationError != null) {
            int requested = extractRequestedCount(userFeedback, chatHistory);
            yaml = synthesizeFallbackYaml(spec, requested);
            versionKey = S3KeyUtil.join(baseKey, "refined_fallback_" + label + ".yaml");
            s3.putString(versionKey, yaml, "text/yaml");
        }

        String canonicalKey = S3KeyUtil.join(baseKey, "tests.yaml");
        s3.putString(canonicalKey, yaml, "text/yaml");

        project.setTestsKey(canonicalKey);
        projects.save(project);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionKey", versionKey);
        out.put("canonicalKey", canonicalKey);
        out.put("activated", true);

        if (autoRun) {
            StartRunRequest req = new StartRunRequest();
            req.setUsername(username);
            req.setProjectName(projectName);
            String task = safeString(manifest.get("task_type"));
            req.setTask((task == null || task.isBlank()) ? "classification" : task);
            var view = runService.startRun(req);
            out.put("newRunId", view.getRunId());
        }

        writeRefinementReceipt(baseKey, art, versionKey, canonicalKey, issues, chatHistory);
        return out;
    }

    private static record DatasetSpec(String family, List<String> requiredKeys) {}

    private DatasetSpec detectDatasetSpec(String baseKey, Map<String, Object> manifest) {
        String family = "tabular";
        List<String> required = List.of("amount", "duration", "age", "is_international");

        Object taskType = manifest.get("task_type");
        if (taskType != null) {
            String tt = String.valueOf(taskType).toLowerCase();
            if (tt.contains("image") || tt.contains("vision")) family = "image";
            else if (tt.contains("text") || tt.contains("nlp")) family = "text";
            else if (tt.contains("tabular") || tt.contains("classification") || tt.contains("regression")) family = "tabular";
        }

        try {
            String pre = S3KeyUtil.join(baseKey, "pre-processed/dataset.csv");
            if (s3.exists(pre)) {
                String sample = s3.getStringSafe(pre, 32_000, 50);
                String firstLine = sample.lines().findFirst().orElse("");
                if (!firstLine.isBlank()) {
                    String[] cols = firstLine.split(",");
                    if (cols.length >= 4) {
                        String lc = firstLine.toLowerCase();
                        if (lc.contains("amount") && lc.contains("duration") && lc.contains("age")) {
                            required = List.of("amount", "duration", "age", "is_international");
                            family = "tabular";
                        } else {
                            List<String> inferred = new ArrayList<>();
                            for (int i = 0; i < Math.min(4, cols.length - 1); i++) inferred.add(cols[i].trim());
                            if (!inferred.isEmpty()) required = inferred;
                        }
                    }
                }
            } else {
                String imgs = S3KeyUtil.join(baseKey, "pre-processed/images/");
                if (s3.existsPrefix(imgs)) family = "image";
                String texts = S3KeyUtil.join(baseKey, "pre-processed/texts/");
                if (s3.existsPrefix(texts)) family = "text";
                String jsons = S3KeyUtil.join(baseKey, "pre-processed/jsons/");
                if (s3.existsPrefix(jsons)) family = "json";
            }
        } catch (Exception ignored) {}

        if (!List.of("tabular", "image", "text", "json").contains(family)) family = "tabular";
        return new DatasetSpec(family, required);
    }

    private String validateYamlSchema(String yaml, DatasetSpec spec) {
        if (yaml == null || yaml.isBlank()) return "YAML is empty";
        if (!yaml.trim().startsWith("tests:")) return "Output must start with 'tests:' section.";
        if (!Pattern.compile("(?m)^scenarios:\\s*$").matcher(yaml).find()) return "Missing 'scenarios:' section.";

        List<Map<String, String>> inputs = extractInputsFromYaml(yaml);
        if (inputs.isEmpty()) return "No scenario 'input' blocks found.";

        for (int i = 0; i < inputs.size(); i++) {
            Map<String, String> kv = inputs.get(i);
            if (kv.isEmpty()) return "Scenario #" + (i + 1) + " has empty input {}.";

            if (spec.family().equals("tabular")) {
                for (String rk : spec.requiredKeys()) {
                    if (!kv.containsKey(rk)) return "Scenario #" + (i + 1) + " is missing required key '" + rk + "'.";
                    String v = kv.get(rk);
                    if (v == null || v.isBlank()) return "Scenario #" + (i + 1) + " has blank value for '" + rk + "'.";
                }
            } else if (spec.family().equals("image")) {
                boolean hasImagePath = kv.keySet().stream().anyMatch(k -> k.toLowerCase().contains("image") || k.toLowerCase().contains("path"));
                if (!hasImagePath) return "Scenario #" + (i + 1) + " must include an image path key.";
            } else if (spec.family().equals("text") || spec.family().equals("json")) {
                boolean hasTextKey = kv.keySet().stream().anyMatch(k -> k.toLowerCase().contains("text") || k.toLowerCase().contains("json") || k.toLowerCase().contains("content"));
                if (!hasTextKey) return "Scenario #" + (i + 1) + " must include a text/json key.";
            }
        }

        if (!Pattern.compile("(?m)expected\\s*:\\s*\\{\\s*kind\\s*:\\s*\"?(classification|regression|unsupervised)\"?").matcher(yaml).find()) {
            return "Ensure scenarios include expected { kind: \"classification\" | \"regression\" | \"unsupervised\" }.";
        }

        if (Pattern.compile("(?m)^\\s*generators:\\s*$").matcher(yaml).find() && spec.family().equals("tabular")) {
            Pattern keyLine = Pattern.compile("(?m)^\\s{6,}([A-Za-z0-9_]+)\\s*:");
            Set<String> seen = new HashSet<>();
            Matcher km = keyLine.matcher(yaml);
            while (km.find()) seen.add(km.group(1));
            for (String rk : spec.requiredKeys()) {
                if (!seen.contains(rk)) return "Generator 'input' is missing key '" + rk + "'.";
            }
        }

        return null;
    }

    private List<Map<String, String>> extractInputsFromYaml(String yaml) {
        Pattern inl = Pattern.compile("(?m)^\\s*input\\s*:\\s*\\{([^}]*)\\}");
        Pattern blk = Pattern.compile("(?m)^\\s*input\\s*:\\s*\\n((?:\\s{4,}.+\\n?)+)");
        List<Map<String, String>> inputs = new ArrayList<>();
        Matcher mi = inl.matcher(yaml);
        while (mi.find()) {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String part : mi.group(1).split(",")) {
                String[] kvp = part.split(":", 2);
                if (kvp.length == 2) kv.put(kvp[0].trim(), kvp[1].trim());
            }
            inputs.add(kv);
        }
        Matcher mb = blk.matcher(yaml);
        while (mb.find()) {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String line : mb.group(1).split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
                String[] kvp = t.split(":", 2);
                if (kvp.length == 2) kv.put(kvp[0].trim(), kvp[1].trim());
            }
            inputs.add(kv);
        }
        return inputs;
    }

    private String synthesizeFallbackYaml(DatasetSpec spec, int requestedCount) {
        // unchanged fallback generator (from your code)
        // ...
        return "tests:\n  # fallback yaml content here"; // keep your full method content
    }

    private String buildStrictBrief(DatasetSpec spec,
                                    Set<String> issues,
                                    Map<String, Object> metrics,
                                    Map<String, Object> manifest,
                                    String chatHistory) {
        // unchanged (from your code)
        return "brief"; // keep your full method content
    }

    private Map<String, Object> readJsonSafe(String s3Key) {
        try {
            if (!s3.exists(s3Key)) return Collections.emptyMap();
            String json = s3.getString(s3Key);
            if (json == null || json.isBlank()) return Collections.emptyMap();
            JsonNode node = om.readTree(json);
            return om.convertValue(node, Map.class);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private Set<String> extractIssues(Map<String, Object> hints, Map<String, Object> manifest) {
        Set<String> out = new LinkedHashSet<>();
        Object a = hints.get("issues");
        if (a instanceof Collection<?> c) c.forEach(x -> { if (x != null) out.add(String.valueOf(x)); });
        Object b = manifest.get("issues");
        if (b instanceof Collection<?> c) c.forEach(x -> { if (x != null) out.add(String.valueOf(x)); });
        return out;
    }

    private String buildContextBundle(String baseKey, List<String> files) {
        StringBuilder ctx = new StringBuilder("### Project files (sampled) ###\n");
        int maxChars = 60_000, csvLines = 200;
        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key)) {
                ctx.append("\n[missing] ").append(rel).append("\n");
                continue;
            }
            String text = s3.getStringSafe(key, maxChars, csvLines);
            ctx.append("\n===== BEGIN FILE: ").append(rel).append(" =====\n")
                    .append(text).append("\n===== END FILE: ").append(rel).append(" =====\n");
        }
        return ctx.toString();
    }

    private void writeRefinementReceipt(String baseKey,
                                        String artifactsPrefix,
                                        String versionKey,
                                        String canonicalKey,
                                        Set<String> issues,
                                        String chatHistory) {
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("artifactsPrefix", artifactsPrefix);
            rec.put("versionKey", versionKey);
            rec.put("canonicalKey", canonicalKey);
            rec.put("issues", new ArrayList<>(issues));
            rec.put("chatHistory", chatHistory);
            rec.put("timestamp", System.currentTimeMillis());

            String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(rec);
            String outKey = S3KeyUtil.join(baseKey, "refinements/refine_" + System.currentTimeMillis() + ".json");
            s3.putString(outKey, json, "application/json");
        } catch (Exception ignored) {}
    }

    private int extractRequestedCount(String userFeedback, String chatHistory) {
        String text = (userFeedback == null ? "" : userFeedback) + "\n" + (chatHistory == null ? "" : chatHistory);
        Matcher m = Pattern.compile("(?:^|\\D)(\\d{1,3})(?:\\s*(?:cases?|testcases?|scenarios?))", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        Matcher m2 = Pattern.compile("generate\\s+(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
        }
        return -1;
    }

    private String safeString(Object o) { return o == null ? null : String.valueOf(o); }
    private Double safeDouble(Object o) {
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}

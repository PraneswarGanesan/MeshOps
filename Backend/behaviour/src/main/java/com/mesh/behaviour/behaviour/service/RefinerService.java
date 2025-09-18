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
 * Robust tests.yaml refiner:
 * - Strict schema: amount, duration, age, is_international (all required for every scenario input)
 * - LLM call -> validate -> retry once -> if still invalid, FALLBACK to local synthesis (valid YAML)
 * - Activates canonical tests.yaml and optionally starts a run
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

    // =================== PUBLIC ENTRY ===================
    public Map<String, Object> refineAndActivateTests(String username,
                                                      String projectName,
                                                      long runId,
                                                      String userFeedback,
                                                      boolean autoRun) {
        // --- validate project & run ---
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Run run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!username.equalsIgnoreCase(run.getUsername()) || !projectName.equalsIgnoreCase(run.getProjectName())) {
            throw new IllegalArgumentException("Run does not belong to specified project");
        }

        // --- persist feedback as chat prompt (if provided) ---
        if (StringUtils.hasText(userFeedback)) {
            scenarioService.savePrompt(username, projectName, userFeedback, runId);
        }

        // --- artifacts & baseKey ---
        String baseKey = S3KeyUtil.keyOf(project.getS3Prefix());
        String art = run.getArtifactsPrefix();

        Map<String, Object> hints    = readJsonSafe(S3KeyUtil.join(art, "refiner_hints.json"));
        Map<String, Object> metrics  = readJsonSafe(S3KeyUtil.join(art, "metrics.json"));
        Map<String, Object> manifest = readJsonSafe(S3KeyUtil.join(art, "manifest.json"));
        Set<String> issues = extractIssues(hints, manifest);
        String chatHistory = scenarioService.buildChatHistory(username, projectName, 12, 4000);

        // --- schema is fixed for this family ---
        List<String> requiredKeys = List.of("amount", "duration", "age", "is_international");
        Set<String> forbiddenKeys = Set.of("feature1", "feature2", "feature3", "feature_1", "feature_2", "feature_3");

        // --- build LLM context & brief ---
        String context = buildContextBundle(baseKey, List.of(
                "driver.py", "tests.yaml", "train.py", "predict.py", "dataset.csv"
        ));
        String baseBrief = buildStrictBrief(issues, metrics, manifest, chatHistory, requiredKeys);

        // attempt LLM -> validate -> retry -> fallback
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

            validationError = validateYamlSchema(yaml, requiredKeys, forbiddenKeys);
            if (validationError == null) break; // valid -> proceed

            correctiveNote =
                    "Your last YAML was rejected because: " + validationError + "\n" +
                            "Fix it and regenerate. IMPORTANT: Every scenario.input MUST include EXACTLY these keys: " +
                            String.join(", ", requiredKeys) + ". No other keys. No empty inputs.\n" +
                            "Generators must include ALL of those keys under 'input:' as arrays.\n" +
                            "Do NOT use feature1/feature2/feature3.";
        }

        if (validationError != null) {
            // -------- FALLBACK: synthesize a valid YAML locally so we never block the user --------
            int requested = extractRequestedCount(userFeedback, chatHistory);
            yaml = synthesizeFallbackYaml(requiredKeys, requested);
            versionKey = S3KeyUtil.join(baseKey, "refined_fallback_" + label + ".yaml");
            s3.putString(versionKey, yaml, "text/yaml");
            // (No exception here; we proceed with activation.)
        }

        // --- activate canonical tests.yaml ---
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

    // =================== VALIDATION ===================
    /** returns null if valid, otherwise human-readable error */
    private String validateYamlSchema(String yaml, List<String> requiredKeys, Set<String> forbiddenKeys) {
        if (yaml == null || yaml.isBlank()) return "YAML is empty";
        if (!yaml.trim().startsWith("tests:")) return "Output must start with 'tests:' section.";

        // forbid feature1/2/3 everywhere
        for (String fk : forbiddenKeys) {
            Pattern p = Pattern.compile("(?m)^\\s*" + Pattern.quote(fk) + "\\s*:");
            if (p.matcher(yaml).find()) return "Forbidden key '" + fk + "' found.";
        }

        if (!Pattern.compile("(?m)^scenarios:\\s*$").matcher(yaml).find()) {
            return "Missing 'scenarios:' section.";
        }

        // capture scenario input (inline/block)
        Pattern inl = Pattern.compile("(?m)^\\s*input\\s*:\\s*\\{([^}]*)\\}");
        Pattern blk = Pattern.compile("(?m)^\\s*input\\s*:\\s*\\n((?:\\s{4,}.+\\n?)+)");

        List<Map<String,String>> inputs = new ArrayList<>();
        Matcher mi = inl.matcher(yaml);
        while (mi.find()) {
            Map<String,String> kv = new LinkedHashMap<>();
            for (String part : mi.group(1).split(",")) {
                String[] kvp = part.split(":",2);
                if (kvp.length==2) kv.put(kvp[0].trim(), kvp[1].trim());
            }
            inputs.add(kv);
        }
        Matcher mb = blk.matcher(yaml);
        while (mb.find()) {
            Map<String,String> kv = new LinkedHashMap<>();
            for (String line : mb.group(1).split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
                String[] kvp = t.split(":",2);
                if (kvp.length==2) kv.put(kvp[0].trim(), kvp[1].trim());
            }
            inputs.add(kv);
        }
        if (inputs.isEmpty()) return "No scenario 'input' blocks found.";
        for (int i=0;i<inputs.size();i++) {
            Map<String,String> kv = inputs.get(i);
            if (kv.isEmpty()) return "Scenario #"+(i+1)+" has empty input {}.";
            for (String rk : requiredKeys) {
                if (!kv.containsKey(rk)) return "Scenario #"+(i+1)+" is missing key '"+rk+"'.";
                String v = kv.get(rk);
                if (v==null || v.isBlank()) return "Scenario #"+(i+1)+" has blank value for '"+rk+"'.";
            }
        }

        // expected.kind/label present somewhere inline (and typically per scenario)
        if (!Pattern.compile("(?m)expected\\s*:\\s*\\{\\s*kind\\s*:\\s*\"?classification\"?\\s*,\\s*label\\s*:\\s*(0|1)\\s*\\}").matcher(yaml).find()) {
            return "Ensure scenarios include expected { kind: \"classification\", label: 0|1 }.";
        }

        // generators: if present, must have all required keys listed
        if (Pattern.compile("(?m)^\\s*generators:\\s*$").matcher(yaml).find()) {
            // grab all lines under generators input: and collect key names
            Pattern keyLine = Pattern.compile("(?m)^\\s{6,}([A-Za-z0-9_]+)\\s*:");
            Set<String> seen = new HashSet<>();
            Matcher km = keyLine.matcher(yaml);
            while (km.find()) {
                seen.add(km.group(1));
            }
            for (String rk: requiredKeys) {
                if (!seen.contains(rk)) return "Generator 'input' is missing key '"+rk+"'.";
            }
        }

        return null;
    }

    // =================== FALLBACK SYNTHESIS ===================
    /**
     * Generates a fully valid tests.yaml covering:
     *  - main run with required assertions
     *  - N scenarios (N=explicit number from chat if present, else 100)
     *  - a generators section with arrays for ALL required keys
     *  - sensible policies
     */
    private String synthesizeFallbackYaml(List<String> requiredKeys, int requestedCount) {
        int N = requestedCount > 0 ? requestedCount : 100;

        // simple patterns to cover boundaries & mixes
        List<int[]> seeds = List.of(
                // amount, duration, age, is_int
                new int[]{  1,  30, 25, 0},   // tiny local legit
                new int[]{500,  60, 35, 0},   // medium local legit
                new int[]{10000,10, 28, 0},   // very high local suspicious
                new int[]{  8, 300, 22, 1},   // small international suspicious
                new int[]{2000,120, 40, 0},   // typical domestic legit
                new int[]{7000,  5,  60, 1},  // high intl suspicious
                new int[]{1200, 30, 70, 0},   // senior domestic mid
                new int[]{ 100,600, 19, 1}    // low amount long duration intl mix
        );

        StringBuilder sb = new StringBuilder();
        sb.append("tests:\n");
        sb.append("  - name: \"Main run\"\n");
        sb.append("    run: \"python driver.py --base_dir=<dir>\"\n");
        sb.append("    assert_stdout_contains:\n");
        sb.append("      - \"Model trained and saved to\"\n");
        sb.append("      - \"Predictions generated.\"\n");
        sb.append("      - \"Evaluation metrics:\"\n");
        sb.append("    assert_file_exists:\n");
        sb.append("      - \"<dir>/model.pkl\"\n");
        sb.append("      - \"<dir>/tests.csv\"\n\n");

        sb.append("scenarios:\n");
        for (int i = 0; i < N; i++) {
            int[] base = seeds.get(i % seeds.size()).clone();
            // add small jitter to avoid duplicates
            base[0] = Math.max(0, base[0] + (i/3)%9);       // amount
            base[1] = Math.max(1, base[1] + (i/5)%20);      // duration
            base[2] = Math.min(80, Math.max(18, base[2] + (i/7)%6)); // age
            base[3] = base[3];                               // is_int

            int amount = base[0];
            int duration = base[1];
            int age = base[2];
            int intl = base[3];

            // heuristic label
            int label = 0;
            if (amount >= 5000) label = 1;
            if (intl == 1 && amount <= 50) label = 1;
            if (intl == 1 && amount >= 1000) label = 1;

            sb.append("  - name: \"Auto scenario ").append(i+1).append("\"\n");
            sb.append("    category: \"Scenario\"\n");
            sb.append("    severity: \"high\"\n");
            sb.append("    input: { amount: ").append(amount)
                    .append(", duration: ").append(duration)
                    .append(", age: ").append(age)
                    .append(", is_international: ").append(intl).append(" }\n");
            sb.append("    expected: { kind: \"classification\", label: ").append(label).append(" }\n");
        }

        sb.append("\n");
        sb.append("generators:\n");
        sb.append("  - name: \"Boundary grid\"\n");
        sb.append("    mode: \"grid\"\n");
        sb.append("    input:\n");
        sb.append("      amount: [0, 1, 10, 100, 500, 2000, 5000, 10000]\n");
        sb.append("      duration: [1, 5, 10, 30, 60, 120, 300, 600]\n");
        sb.append("      age: [18, 25, 35, 45, 55, 65, 75, 80]\n");
        sb.append("      is_international: [0, 1]\n");
        sb.append("    expected: { kind: \"classification\" }\n\n");

        sb.append("policies:\n");
        sb.append("  majority_baseline_margin: 0.02\n");
        sb.append("  binary_avg: \"binary\"\n");
        sb.append("  multiclass_avg: \"weighted\"\n");
        sb.append("  zero_division: 0\n");
        sb.append("  regression_tolerance_factor: 2.0\n");
        sb.append("  require_confusion_matrix: true\n");

        return sb.toString();
    }

    /** tries to read an explicit number like "generate 6", "6 testcases", etc. from feedback/history */
    private int extractRequestedCount(String userFeedback, String chatHistory) {
        String text = (userFeedback == null ? "" : userFeedback) + "\n" + (chatHistory == null ? "" : chatHistory);
        Matcher m = Pattern.compile("(?:^|\\D)(\\d{1,3})(?:\\s*(?:cases?|testcases?|scenarios?))", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        // also catch "Generate 5 ..." patterns
        Matcher m2 = Pattern.compile("generate\\s+(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE).matcher(text);
        if (m2.find()) {
            try { return Integer.parseInt(m2.group(1)); } catch (Exception ignored) {}
        }
        return -1;
    }

    // =================== PROMPT BUILDER ===================
    private String buildStrictBrief(Set<String> issues,
                                    Map<String, Object> metrics,
                                    Map<String, Object> manifest,
                                    String chatHistory,
                                    List<String> requiredKeys) {

        String taskType = safeString(metrics.get("task_type"));
        String pipeline = safeString(manifest.get("pipeline"));
        Double acc = safeDouble(metrics.get("accuracy"));
        Double f1 = safeDouble(metrics.get("f1"));
        Double mae = safeDouble(metrics.get("mae"));
        Double rmse = safeDouble(metrics.get("rmse"));
        Double uniquePreds = safeDouble(metrics.get("unique_preds"));
        Double baseline = safeDouble(metrics.get("majority_baseline_acc"));
        String rk = String.join(", ", requiredKeys);

        StringBuilder sb = new StringBuilder();
        sb.append("""
            You are rewriting tests.yaml for behaviour testing of a CREDIT CARD FRAUD DETECTION project.

            ABSOLUTE RULES (hard fail if broken):
            - Use ONLY these input keys for EVERY scenario: %s
              * No other names (do NOT use feature1/feature2/feature3).
              * EVERY scenario MUST include ALL of those keys with concrete numeric/integer values.
            - expected.kind MUST be "classification" and expected.label MUST be 0 or 1.
            - Do NOT leave any scenario.input empty ({} is invalid).
            - The YAML must START with:
              tests:
            - Output ONLY YAML (no markdown fences or prose).

            RUNTIME ASSERTIONS (tests[0]):
            - run: "python driver.py --base_dir=<dir>"
            - assert_stdout_contains:
                - "Model trained and saved to"
                - "Predictions generated."
                - "Evaluation metrics:"
            - assert_file_exists:
                - "<dir>/model.pkl"
                - "<dir>/tests.csv"

            SCENARIO COUNT POLICY:
            - If the most recent user prompt asks for an exact NUMBER of scenarios (e.g., "Generate 5" / "6 testcases"):
              produce EXACTLY that many scenarios.
            - Otherwise, produce AT LEAST 100 realistic scenarios (boundary + typical + mixed).
            - Scenario names should be unique and descriptive.

            VALUE RANGES (guidance):
            - amount: [0..10], ~100..600, ~1000..3000, 5000..10000
            - duration: 1, 5, 10, 30, 60, 120, 300, 600
            - age: 18..80 (integer)
            - is_international: {0,1}

            LABELING HINTS (examples):
            - Very small local (amount 0–10, is_international=0) → label 0
            - Medium local (~500) → label 0
            - Very high local (10000) → label 1
            - Small amount but international → label 1
            - Typical domestic (~2000), age mid (~40) → label 0

            OUTPUT SECTIONS (exact order):
            - tests:
            - scenarios:
            - generators:
            - policies:
            """.formatted(rk));

        if (!issues.isEmpty()) {
            sb.append("\nSYSTEM-DETECTED ISSUES:\n");
            for (String iss : issues) sb.append("- ").append(iss).append('\n');
        }
        if (StringUtils.hasText(chatHistory)) {
            sb.append("\nMOST RECENT USER REQUESTS (FOLLOW THEM, newest last):\n").append(chatHistory).append("\n");
        }

        sb.append("\nCONTEXT SUMMARY:\n");
        sb.append("- task_type: ").append(taskType == null ? "unknown" : taskType).append('\n');
        sb.append("- pipeline: ").append(pipeline == null ? "unknown" : pipeline).append('\n');
        if (acc != null) sb.append("- accuracy: ").append(String.format("%.4f", acc)).append('\n');
        if (f1 != null)  sb.append("- f1: ").append(String.format("%.4f", f1)).append('\n');
        if (mae != null) sb.append("- mae: ").append(String.format("%.4f", mae)).append('\n');
        if (rmse != null) sb.append("- rmse: ").append(String.format("%.4f", rmse)).append('\n');
        if (baseline != null) sb.append("- majority_baseline_acc: ").append(String.format("%.4f", baseline)).append('\n');
        if (uniquePreds != null) sb.append("- unique_preds: ").append(uniquePreds.longValue()).append('\n');

        sb.append("""
            
            ROBUSTNESS/REFINEMENT:
            - If predictions collapsed to a single class (unique_preds <= 1), keep tests that would fail that case.
            - Keep commands Ubuntu/Python3 friendly; do NOT install packages.

            FINAL OUTPUT:
            Return ONLY YAML with the sections above. Start with:
              tests:
            """);
        return sb.toString();
    }

    // =================== HELPERS ===================
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

    private String safeString(Object o) { return o == null ? null : String.valueOf(o); }
    private Double safeDouble(Object o) {
        if (o == null) return null;
        try { return Double.valueOf(String.valueOf(o)); } catch (Exception e) { return null; }
    }
}

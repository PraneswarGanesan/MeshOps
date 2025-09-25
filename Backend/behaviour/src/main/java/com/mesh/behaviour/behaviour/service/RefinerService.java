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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefinerService {

    private final S3Service s3;
    private final LlmService llm;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final RunService runService;
    private final ScenarioService scenarioService;

    private final ObjectMapper om = new ObjectMapper();

    /**
     * Refine tests for a run/project and activate canonical tests.yaml in version folder.
     */
    public Map<String, Object> refineAndActivateTests(
            String username,
            String projectName,
            String versionLabel,
            long runId,
            String userFeedback,
            boolean autoRun
    ) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - RefinerService cannot create versions");
        }

        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Run run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        if (!username.equalsIgnoreCase(run.getUsername()) ||
                !projectName.equalsIgnoreCase(run.getProjectName())) {
            throw new IllegalArgumentException("Run does not belong to specified project");
        }

        if (StringUtils.hasText(userFeedback)) {
            scenarioService.savePrompt(username, projectName, versionLabel, userFeedback, runId);
        }

        // Root = project root
        String root = S3KeyUtil.keyOf(project.getS3Prefix());
        while (root.startsWith("/")) root = root.substring(1);
        if (root.contains("/artifacts/versions/")) {
            root = root.substring(0, root.indexOf("/artifacts/versions/"));
        }

        // Use provided version folder - NO VERSION CREATION
        String versionBase = S3KeyUtil.join(root, "artifacts/versions", versionLabel);
        if (!versionBase.endsWith("/")) versionBase = versionBase + "/";

        // Behaviour tests folder inside version
        String behaviourBase = S3KeyUtil.join(versionBase, "behaviour-tests/");
        if (!s3.existsPrefix(behaviourBase)) {
            s3.putString(S3KeyUtil.join(behaviourBase, ".keep"), "", "text/plain");
            log.info("Created behaviour-tests/ folder under {}", versionBase);
        }

        log.info("RefinerService working within version: {} at path: {}", versionLabel, versionBase);

        Map<String, Object> hints = readJsonSafe(S3KeyUtil.join(versionBase, "refiner_hints.json"));
        Map<String, Object> metrics = readJsonSafe(S3KeyUtil.join(versionBase, "metrics.json"));
        Map<String, Object> manifest = readJsonSafe(S3KeyUtil.join(versionBase, "manifest.json"));
        Set<String> issues = extractIssues(hints, manifest);

        String chatHistory = scenarioService.buildChatHistory(username, projectName, versionLabel, 12, 4000);

        // Context bundle
        String context = buildContextBundle(root, List.of(
                "pre-processed/train.py",
                "pre-processed/predict.py",
                "pre-processed/dataset.csv",
                "pre-processed/images/",
                "pre-processed/texts/"
        ));
        context += buildContextBundle(versionBase, List.of("driver.py", "tests.yaml"));

        String baseBrief = buildBrief(issues, metrics, manifest, chatHistory);

        String label = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // === Generate once from Gemini and accept it (no validation, no fallback) ===
        Map<String, String> gen = llm.generateTestsOnlyToS3(
                baseBrief,
                context,
                behaviourBase,
                "refined_" + label
        );
        String versionKey = gen.get("versionKey");
        String yaml = s3.getString(versionKey);

        // Archive old tests.yaml before overwriting
        String canonicalKey = S3KeyUtil.join(versionBase, "tests.yaml");
        if (s3.exists(canonicalKey)) {
            String oldYaml = s3.getString(canonicalKey);
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String archiveKey = S3KeyUtil.join(behaviourBase, "tests_" + timestamp + ".yaml");
            s3.putString(archiveKey, oldYaml, "text/yaml");
            log.info("Archived old tests.yaml to: {}", archiveKey);
        }

        // Overwrite canonical tests.yaml inside current version folder
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

        writeRefinementReceipt(root, behaviourBase, versionKey, canonicalKey, issues, chatHistory);
        return out;
    }

    private void writeRefinementReceipt(
            String root,
            String behaviourBase,
            String versionKey,
            String canonicalKey,
            Set<String> issues,
            String chatHistory
    ) {
        try {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("root", root);
            rec.put("behaviourBase", behaviourBase);
            rec.put("versionKey", versionKey);
            rec.put("canonicalKey", canonicalKey);
            rec.put("issues", issues);
            rec.put("chatHistory", chatHistory);
            rec.put("timestamp", System.currentTimeMillis());
            String outKey = S3KeyUtil.join(behaviourBase, "refinement_receipt.json");
            s3.putString(outKey, om.writeValueAsString(rec), "application/json");
        } catch (Exception ignored) {}
    }

    /* ==================== Helpers ==================== */

    private Map<String, Object> readJsonSafe(String key) {
        try {
            if (!s3.exists(key)) return Collections.emptyMap();
            String json = s3.getString(key);
            if (!StringUtils.hasText(json)) return Collections.emptyMap();
            JsonNode node = om.readTree(json);
            return om.convertValue(node, Map.class);
        } catch (Exception e) {
            log.warn("Failed to read JSON from {}: {}", key, e.getMessage());
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
        StringBuilder ctx = new StringBuilder();
        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key)) {
                ctx.append("[missing] ").append(rel).append("\n");
                continue;
            }
            String text = s3.getStringSafe(key, 5000, 100);
            ctx.append("\n===== BEGIN FILE: ").append(rel).append(" =====\n")
                    .append(text).append("\n===== END FILE =====\n");
        }
        return ctx.toString();
    }

    private String buildBrief(Set<String> issues,
                              Map<String, Object> metrics,
                              Map<String, Object> manifest,
                              String chatHistory) {
        StringBuilder b = new StringBuilder();
        b.append("You are refining a tests.yaml for behaviour testing.\n");
        b.append("Rules: output ONLY YAML with tests: and scenarios:.\n");
        if (metrics != null && !metrics.isEmpty()) b.append("Known metrics: ").append(metrics).append("\n");
        if (issues != null && !issues.isEmpty()) b.append("Known issues: ").append(issues).append("\n");
        if (chatHistory != null && !chatHistory.isBlank()) b.append("Recent notes: ").append(chatHistory).append("\n");
        return b.toString();
    }

    private String safeString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

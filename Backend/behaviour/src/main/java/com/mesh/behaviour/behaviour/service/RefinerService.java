// src/main/java/com/mesh/behaviour/behaviour/service/RefinerService.java
package com.mesh.behaviour.behaviour.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mesh.behaviour.behaviour.dto.StartRunRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.model.ScenarioPrompt;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
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
    private final RunService runService;                // still needed for autoRun
    private final ScenarioService scenarioService;

    private final ObjectMapper om = new ObjectMapper();

    /**
     * Main entry point: Refine tests using a specific promptId
     */
    public Map<String, Object> refineAndActivateByPrompt(
            String username,
            String projectName,
            String versionLabel,
            long promptId,
            boolean autoRun
    ) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required");
        }

        // ✅ Fetch the selected prompt
        ScenarioPrompt prompt = scenarioService
                .listPrompts(username, projectName, versionLabel)
                .stream()
                .filter(p -> p.getId() == promptId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Prompt not found: " + promptId));

        // Strip [user] prefix if present
        String userPrompt = prompt.getMessage();
        if (userPrompt != null && userPrompt.startsWith("[user]")) {
            userPrompt = userPrompt.substring(6).trim();
        }

        return refineAndActivateCore(username, projectName, versionLabel, userPrompt, autoRun);
    }

    /**
     * Core refinement logic – builds context and sends latest prompt to LLM
     */
    private Map<String, Object> refineAndActivateCore(
            String username,
            String projectName,
            String versionLabel,
            String userPrompt,
            boolean autoRun
    ) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        // Save prompt again for traceability
        if (StringUtils.hasText(userPrompt)) {
            scenarioService.savePrompt(username, projectName, versionLabel, userPrompt, null);
        }

        // Resolve S3 base
        String root = S3KeyUtil.keyOf(project.getS3Prefix());
        while (root.startsWith("/")) root = root.substring(1);
        if (root.contains("/artifacts/versions/")) {
            root = root.substring(0, root.indexOf("/artifacts/versions/"));
        }

        // Version folder
        String versionBase = S3KeyUtil.join(root, "artifacts/versions", versionLabel);
        if (!versionBase.endsWith("/")) versionBase += "/";

        // Behaviour-tests folder
        String behaviourBase = S3KeyUtil.join(versionBase, "behaviour-tests/");
        if (!s3.existsPrefix(behaviourBase)) {
            s3.putString(S3KeyUtil.join(behaviourBase, ".keep"), "", "text/plain");
            log.info("Created behaviour-tests/ folder under {}", versionBase);
        }

        log.info("RefinerService working within version: {} at {}", versionLabel, versionBase);

        // Read optional metadata
        Map<String, Object> hints = readJsonSafe(S3KeyUtil.join(versionBase, "refiner_hints.json"));
        Map<String, Object> metrics = readJsonSafe(S3KeyUtil.join(versionBase, "metrics.json"));
        Map<String, Object> manifest = readJsonSafe(S3KeyUtil.join(versionBase, "manifest.json"));
        Set<String> issues = extractIssues(hints, manifest);

        // Build context
        String context = buildContextBundle(root, List.of(
                "pre-processed/train.py",
                "pre-processed/predict.py",
                "pre-processed/dataset.csv",
                "pre-processed/images/",
                "pre-processed/texts/"
        ));
        context += buildContextBundle(versionBase, List.of("driver.py", "tests.yaml"));

        // ✅ Compose brief with ONLY the latest user prompt
        String baseBrief = buildBrief(issues, metrics, manifest, userPrompt);
        String label = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        // Generate tests.yaml with Gemini
        Map<String, String> gen = llm.generateTestsOnlyToS3(
                baseBrief,
                context,
                behaviourBase,
                "refined_" + label
        );
        String versionKey = gen.get("versionKey");
        String yaml = s3.getString(versionKey);

        // Archive old canonical YAML
        String canonicalKey = S3KeyUtil.join(versionBase, "tests.yaml");
        if (s3.exists(canonicalKey)) {
            String oldYaml = s3.getString(canonicalKey);
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String archiveKey = S3KeyUtil.join(behaviourBase, "tests_" + ts + ".yaml");
            s3.putString(archiveKey, oldYaml, "text/yaml");
            log.info("Archived previous tests.yaml to {}", archiveKey);
        }

        // Save new canonical YAML
        s3.putString(canonicalKey, yaml, "text/yaml");
        project.setTestsKey(canonicalKey);
        projects.save(project);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versionKey", versionKey);
        out.put("canonicalKey", canonicalKey);
        out.put("activated", true);

        // Auto-run pipeline if requested
        if (autoRun) {
            StartRunRequest req = new StartRunRequest();
            req.setUsername(username);
            req.setProjectName(projectName);
            String task = safeString(manifest.get("task_type"));
            req.setTask((task == null || task.isBlank()) ? "classification" : task);
            var view = runService.startRun(req);
            out.put("newRunId", view.getRunId());
        }

        return out;
    }

    /* ---------------------- Helpers ---------------------- */

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
                              String userPrompt) {

        StringBuilder b = new StringBuilder();
        b.append("You are generating ONLY a tests.yaml for behaviour testing.\n");
        b.append("All instructions come strictly from the USER PROMPT below.\n");
        b.append("Do NOT add default tests unless the user explicitly asks.\n");
        b.append("Use dataset paths only from CONTEXT.\n");
        b.append("Output must start with:\n");
        b.append("tests:\n  scenarios:\n");
        b.append("Use double quotes for all string values.\n");

        if (StringUtils.hasText(userPrompt)) {
            b.append("USER PROMPT: ").append(userPrompt).append("\n");
        }

        if (metrics != null && !metrics.isEmpty())
            b.append("Known metrics: ").append(metrics).append("\n");
        if (issues != null && !issues.isEmpty())
            b.append("Known issues: ").append(issues).append("\n");

        return b.toString();
    }


    private String safeString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}

package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.dto.ActivateTestsRequest;
import com.mesh.behaviour.behaviour.dto.ApprovePlanRequest;
import com.mesh.behaviour.behaviour.dto.GeneratePlanRequest;
import com.mesh.behaviour.behaviour.dto.GenerateTestsRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final ProjectRepository projects;
    private final S3Service s3;
    private final LlmService llm;

    // ===================== EXISTING: Generate driver.py + tests.yaml =====================

    /**
     * Calls LLM to generate driver.py + tests.yaml, writes them to S3 under the project's s3Prefix.
     * Returns S3 keys and file contents (trimmed/safe) for frontend preview.
     */
    @Transactional
    public Mono<Map<String, String>> generateAndSave(String username,
                                                     String projectName,
                                                     GeneratePlanRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = S3KeyUtil.keyOf(project.getS3Prefix());

        // Build context from selected files
        StringBuilder ctx = new StringBuilder();
        ctx.append("### User-selected project files (content included/truncated) ###\n");

        List<String> files = req.getFiles();
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files list is required");
        }

        int maxCharsPerFile = 60_000;
        int csvMaxLines = 150;

        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key)) {
                ctx.append("\n[missing] ").append(rel).append("\n");
                continue;
            }
            if (isTextLike(rel)) {
                String text = s3.getStringSafe(key, maxCharsPerFile, csvMaxLines);
                ctx.append("\n===== BEGIN FILE: ").append(rel).append(" =====\n")
                        .append(text)
                        .append("\n===== END FILE: ").append(rel).append(" =====\n");
            } else {
                ctx.append("\n[binary or large file omitted] ").append(rel).append("\n");
            }
        }

        // Call LLM → directly save driver/tests into S3
        return llm.generateAndSaveToS3(req.getBrief(), ctx.toString(), baseKey)
                .map(keys -> {
                    // update project with new keys, mark NOT approved yet
                    project.setDriverKey(keys.get("driverKey"));
                    project.setTestsKey(keys.get("testsKey"));
                    project.setApproved(false);
                    projects.save(project);

                    return Map.of(
                            "driverKey", keys.get("driverKey"),
                            "testsKey", keys.get("testsKey"),
                            "driverContent", s3.getStringSafe(keys.get("driverKey"), 100_000, 200),
                            "testsContent", s3.getStringSafe(keys.get("testsKey"), 50_000, 200)
                    );
                });
    }

    // ===================== EXISTING: Approve plan =====================

    @Transactional
    public Project approvePlan(ApprovePlanRequest req) {
        Project project = projects.findByUsernameAndProjectName(req.getUsername(), req.getProjectName())
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        if (req.getDriverKey() != null && !req.getDriverKey().isBlank()) {
            project.setDriverKey(req.getDriverKey().trim());
        }
        if (req.getTestsKey() != null && !req.getTestsKey().isBlank()) {
            project.setTestsKey(req.getTestsKey().trim());
        }
        if (req.getS3Prefix() != null && !req.getS3Prefix().isBlank()) {
            project.setS3Prefix(req.getS3Prefix().trim());
        }

        project.setApproved(req.getApproved() != null ? req.getApproved() : false);

        return projects.save(project);
    }

    // ===================== NEW: List canonical + versioned tests =====================

    @Transactional(readOnly = true)
    public Map<String, Object> listAllTests(String username, String projectName) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = S3KeyUtil.keyOf(project.getS3Prefix());
        String canonicalKey = S3KeyUtil.join(baseKey, "tests.yaml");
        String versionsPrefix = S3KeyUtil.join(baseKey, "tests/");

        List<String> versions = s3.listKeys(versionsPrefix).stream()
                .filter(k -> k.toLowerCase().endsWith(".yaml") || k.toLowerCase().endsWith(".yml"))
                .sorted()
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("canonicalKey", canonicalKey);
        out.put("canonicalExists", s3.exists(canonicalKey));
        out.put("versions", versions);
        return out;
    }

    // ===================== NEW: Generate fresh tests (versioned) =====================

    /**
     * Generate a new tests.yaml from LLM and save it under <baseKey>/tests/tests_<label>.yaml.
     * Optionally activate it (copy into canonical tests.yaml and update project.testsKey).
     * Driver is NOT modified here.
     */
    @Transactional
    public Map<String, String> generateNewTests(String username,
                                                String projectName,
                                                GenerateTestsRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = S3KeyUtil.keyOf(
                (req.getS3Prefix() != null && !req.getS3Prefix().isBlank())
                        ? req.getS3Prefix()
                        : project.getS3Prefix()
        );

        // Build context bundle (similar policy to generateAndSave)
        List<String> files = (req.getFiles() == null || req.getFiles().isEmpty())
                ? List.of("train.py", "predict.py", "dataset.csv")
                : req.getFiles();

        StringBuilder ctx = new StringBuilder("### Context files for behaviour tests ###\n");
        int maxCharsPerFile = 60_000, csvMaxLines = 150;
        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key)) {
                ctx.append("\n[missing] ").append(rel).append("\n");
                continue;
            }
            if (isTextLike(rel)) {
                String text = s3.getStringSafe(key, maxCharsPerFile, csvMaxLines);
                ctx.append("\n===== BEGIN FILE: ").append(rel).append(" =====\n")
                        .append(text)
                        .append("\n===== END FILE: ").append(rel).append(" =====\n");
            } else {
                ctx.append("\n[binary or large file omitted] ").append(rel).append("\n");
            }
        }

        String label = (req.getVersionLabel() != null && !req.getVersionLabel().isBlank())
                ? req.getVersionLabel().replaceAll("[^a-zA-Z0-9_\\-\\.]", "_")
                : new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());

        Map<String, String> keys = llm.generateTestsOnlyToS3(
                Optional.ofNullable(req.getBrief()).orElse("Create robust behaviour tests for current model."),
                ctx.toString(),
                baseKey,
                label
        );

        // Optionally activate immediately (copy versioned → canonical; update project)
        if (Boolean.TRUE.equals(req.getActivate())) {
            String versionKey = keys.get("versionKey");
            String canonicalKey = keys.get("canonicalKey");

            // S3Service has no copy; emulate by read & write
            String content = s3.getString(versionKey);
            s3.putString(canonicalKey, content, "text/yaml");

            project.setTestsKey(canonicalKey);
            projects.save(project);

            keys = Map.of(
                    "versionKey", versionKey,
                    "canonicalKey", canonicalKey,
                    "activated", "true"
            );
        }

        return keys;
    }

    // ===================== NEW: Activate a chosen version as canonical =====================

    @Transactional
    public Map<String, String> activateTests(String username,
                                             String projectName,
                                             ActivateTestsRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = S3KeyUtil.keyOf(
                (req.getS3Prefix() != null && !req.getS3Prefix().isBlank())
                        ? req.getS3Prefix()
                        : project.getS3Prefix()
        );

        String versionKey = Objects.requireNonNull(req.getKey(), "key is required").trim();
        if (!s3.exists(versionKey)) throw new IllegalArgumentException("tests key not found: " + versionKey);

        String canonicalKey = S3KeyUtil.join(baseKey, "tests.yaml");

        // S3Service has no copy; emulate by read & write
        String content = s3.getString(versionKey);
        s3.putString(canonicalKey, content, "text/yaml");

        project.setTestsKey(canonicalKey);
        projects.save(project);

        return Map.of("canonicalKey", canonicalKey, "activatedFrom", versionKey);
    }

    // ===================== helpers =====================

    private boolean isTextLike(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".py") || n.endsWith(".txt") || n.endsWith(".md")
                || n.endsWith(".json") || n.endsWith(".yaml") || n.endsWith(".yml")
                || n.endsWith(".csv") || n.endsWith(".tsv");
    }
}

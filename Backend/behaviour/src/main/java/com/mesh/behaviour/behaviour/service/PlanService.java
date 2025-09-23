// src/main/java/com/mesh/behaviour/behaviour/service/PlanService.java
package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.dto.GeneratePlanRequest;
import com.mesh.behaviour.behaviour.dto.GenerateTestsRequest;
import com.mesh.behaviour.behaviour.dto.ApprovePlanRequest;
import com.mesh.behaviour.behaviour.dto.ActivateTestsRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.repository.ProjectRepository;
import com.mesh.behaviour.behaviour.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PlanService {

    private final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final ProjectRepository projects;
    private final S3Service s3;
    private final LlmService llm;

    private static final String ARTIFACTS_PREFIX = "artifacts/versions/";

    @Transactional
    public Mono<Map<String, String>> generateAndSave(String username,
                                                     String projectName,
                                                     GeneratePlanRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = normalizeBaseKey(project.getS3Prefix());

        if (baseKey.endsWith("/artifacts") || baseKey.endsWith("/artifacts/")) {
            baseKey = baseKey.replaceAll("/?artifacts/?$", "");
        }

        String versionPrefix = createNewVersionSnapshot(baseKey);

        StringBuilder ctx = new StringBuilder("### Context for analysis ###\n");
        for (String rel : List.of("train.py", "predict.py", "dataset.csv", "images/", "texts/", "hf_model/")) {
            String key = S3KeyUtil.join(baseKey, rel.replaceFirst("^/+", ""));
            if (s3.exists(key) || s3.existsPrefix(key)) {
                ctx.append("\n[file] ").append(rel).append("\n");
                ctx.append(s3.getStringSafe(key, 30_000, 100)).append("\n");
            }
        }

        return llm.generateDriverUsingAdaptivePrompt(
                Optional.ofNullable(req.getBrief()).orElse(""),
                ctx.toString(),
                baseKey,
                versionPrefix,
                true
        ).map(keys -> {
            project.setDriverKey(keys.get("driverKey"));
            project.setTestsKey(keys.get("testsKey"));
            project.setApproved(false);
            projects.save(project);

            return Map.of(
                    "driverKey", keys.get("driverKey"),
                    "testsKey", keys.get("testsKey"),
                    "analysisKey", keys.getOrDefault("analysisKey", ""),
                    "driverContent", s3.getStringSafe(keys.get("driverKey"), 100_000, 200),
                    "testsContent", s3.getStringSafe(keys.get("testsKey"), 50_000, 200)
            );
        });
    }

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

    @Transactional(readOnly = true)
    public Map<String, Object> listAllTests(String username, String projectName) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = normalizeBaseKey(project.getS3Prefix());
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

    @Transactional
    public Map<String, String> generateNewTests(String username,
                                                String projectName,
                                                GenerateTestsRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = normalizeBaseKey(
                (req.getS3Prefix() != null && !req.getS3Prefix().isBlank())
                        ? req.getS3Prefix()
                        : project.getS3Prefix()
        );

        List<String> files = (req.getFiles() == null || req.getFiles().isEmpty())
                ? List.of("train.py", "predict.py", "dataset.csv")
                : req.getFiles();

        StringBuilder ctx = new StringBuilder("### Context files for behaviour tests ###\n");
        int maxCharsPerFile = 60_000, csvMaxLines = 150;
        for (String rel : files) {
            String key = S3KeyUtil.join(baseKey, rel);
            if (!s3.exists(key) && !s3.existsPrefix(key)) {
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

        if (Boolean.TRUE.equals(req.getActivate())) {
            String versionKey = keys.get("versionKey");
            String canonicalKey = keys.get("canonicalKey");
            String content = s3.getString(versionKey);
            s3.putString(canonicalKey, content, "text/yaml");
            project.setTestsKey(canonicalKey);
            projects.save(project);

            keys = Map.of("versionKey", versionKey, "canonicalKey", canonicalKey, "activated", "true");
        }

        return keys;
    }

    @Transactional
    public Map<String, String> activateTests(String username,
                                             String projectName,
                                             ActivateTestsRequest req) {
        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));

        String baseKey = normalizeBaseKey(
                (req.getS3Prefix() != null && !req.getS3Prefix().isBlank())
                        ? req.getS3Prefix()
                        : project.getS3Prefix()
        );

        String versionKey = Objects.requireNonNull(req.getKey(), "key is required").trim();
        if (!s3.exists(versionKey)) throw new IllegalArgumentException("tests key not found: " + versionKey);

        String canonicalKey = S3KeyUtil.join(baseKey, "tests.yaml");
        String content = s3.getString(versionKey);
        s3.putString(canonicalKey, content, "text/yaml");

        project.setTestsKey(canonicalKey);
        projects.save(project);

        return Map.of("canonicalKey", canonicalKey, "activatedFrom", versionKey);
    }

    private String createNewVersionSnapshot(String baseKey) {
        String versionsRoot = S3KeyUtil.join(baseKey, ARTIFACTS_PREFIX);
        List<String> keys = s3.listKeys(versionsRoot);
        int maxV = -1;
        for (String k : keys) {
            String normalized = k.startsWith(versionsRoot) ? k.substring(versionsRoot.length()) : k;
            String[] parts = normalized.split("/");
            if (parts.length > 0 && parts[0].matches("v\\d+")) {
                try {
                    int num = Integer.parseInt(parts[0].substring(1));
                    maxV = Math.max(maxV, num);
                } catch (NumberFormatException ignored) {}
            }
        }
        int newV = Math.max(1, maxV + 1);
        return S3KeyUtil.join(versionsRoot, "v" + newV + "/");
    }

    private boolean isTextLike(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".py") || n.endsWith(".txt") || n.endsWith(".md")
                || n.endsWith(".json") || n.endsWith(".yaml") || n.endsWith(".yml")
                || n.endsWith(".csv") || n.endsWith(".tsv");
    }

    private String normalizeBaseKey(String s3PrefixOrKey) {
        if (s3PrefixOrKey == null || s3PrefixOrKey.isBlank()) {
            throw new IllegalArgumentException("Missing s3Prefix");
        }
        String in = s3PrefixOrKey.trim();
        if (in.startsWith("s3://")) return S3KeyUtil.keyOf(in);
        if (in.startsWith("/")) in = in.substring(1);
        while (in.endsWith("/")) in = in.substring(0, in.length() - 1);
        return in;
    }
}

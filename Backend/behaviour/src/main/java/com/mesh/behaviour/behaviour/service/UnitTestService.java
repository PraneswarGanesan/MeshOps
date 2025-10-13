// src/main/java/com/mesh/behaviour/behaviour/service/UnitTestService.java
package com.mesh.behaviour.behaviour.service;

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
public class UnitTestService {

    private final S3Service s3;
    private final LlmService llm;
    private final ProjectRepository projects;
    private final RunRepository runs;
    private final RunService runService;
    private final UnitTestScenarioService unitScenarioService; // ✅ correct service
    private final ObjectMapper om = new ObjectMapper();

    public Map<String, Object> refineAndActivateUnitTests(
            String username,
            String projectName,
            String versionLabel,
            long runId,
            String userFeedback,
            boolean autoRun
    ) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - UnitTestService");
        }

        Project project = projects.findByUsernameAndProjectName(username, projectName)
                .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        Run run = runs.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Run {} not found. Proceeding without run linkage.", runId);
            run = new Run();
            run.setId(runId);
            run.setUsername(username);
            run.setProjectName(projectName);
        }



        if (!username.equalsIgnoreCase(run.getUsername()) ||
                !projectName.equalsIgnoreCase(run.getProjectName())) {
            throw new IllegalArgumentException("Run does not belong to specified project");
        }

        // ✅ Save prompt in unit test scenario table
        if (StringUtils.hasText(userFeedback)) {
            unitScenarioService.savePrompt(username, projectName, versionLabel, userFeedback, runId);
        }

        String root = S3KeyUtil.keyOf(project.getS3Prefix());
        while (root.startsWith("/")) root = root.substring(1);
        if (root.contains("/artifacts/versions/")) {
            root = root.substring(0, root.indexOf("/artifacts/versions/"));
        }

        String versionBase = S3KeyUtil.join(root, "artifacts/versions", versionLabel);
        if (!versionBase.endsWith("/")) versionBase = versionBase + "/";

        String unitBase = S3KeyUtil.join(versionBase, "unit-tests/");
        if (!s3.existsPrefix(unitBase)) {
            s3.putString(S3KeyUtil.join(unitBase, ".keep"), "", "text/plain");
            log.info("Created unit-tests/ folder under {}", versionBase);
        }

        // ✅ Build chat history using unitScenarioService
        String chatHistory = unitScenarioService.buildChatHistory(username, projectName, versionLabel, 12, 4000);

        String baseBrief = """
You are generating/refining a tests.yaml for **UNIT TESTING** of an ML project.

Rules:
- Output ONLY YAML (no markdown fences, no prose).
- YAML must start with "tests:" and include "scenarios:".
- Each scenario should test a FUNCTION or COMPONENT (not just model behaviour).
- Supported keys:
  - name: test name
  - function: function under test (e.g., load_data, train_model, predict, predict_proba)
  - input: optional input (file path, sample, or parameters)
  - expected: expected result keyword (e.g., non_empty, model_saved, correct_shape, probability_sum)
- Keep tests small and invariant-driven, not dataset-heavy.

Recent notes:
""" + chatHistory;

        String label = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());

        String canonicalKey = S3KeyUtil.join(versionBase, "tests.yaml");
        String versionKey = generateAndActivate(baseBrief, chatHistory, unitBase, canonicalKey, "unit_" + label);

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
            req.setTask("unit");
            var view = runService.startRun(req);
            out.put("newRunId", view.getRunId());
        }

        return out;
    }

    private String generateAndActivate(String brief, String context, String folderBase, String canonicalKey, String label) {
        Map<String, String> gen = llm.generateTestsOnlyToS3(brief, context, folderBase, label);
        String versionKey = gen.get("versionKey");
        String yaml = s3.getString(versionKey);

        s3.putString(canonicalKey, yaml, "text/yaml");
        return versionKey;
    }
}

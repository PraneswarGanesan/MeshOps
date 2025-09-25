// src/main/java/com/mesh/behaviour/behaviour/service/UnitTestScenarioService.java
package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.model.UnitTestScenarioPrompt;
import com.mesh.behaviour.behaviour.repository.UnitTestScenarioPromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UnitTestScenarioService {

    private final UnitTestScenarioPromptRepository repo;
    private static final int MAX_MESSAGE_CHARS = 2000;

    public UnitTestScenarioPrompt savePrompt(String username, String projectName, String versionLabel, String message, Long runId) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - UnitTestScenarioService");
        }
        if (!StringUtils.hasText(username) || !StringUtils.hasText(projectName) || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException("username, projectName and message are required");
        }

        String trimmed = message.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_CHARS - 16) + " ...[truncated]";
        }

        UnitTestScenarioPrompt sp = UnitTestScenarioPrompt.builder()
                .username(username.trim())
                .projectName(projectName.trim())
                .versionLabel(versionLabel.trim())
                .message("[" + "user" + "] " + trimmed)
                .runId(runId)
                .createdAt(new Timestamp(System.currentTimeMillis()))
                .build();
        return repo.save(sp);
    }

    public List<UnitTestScenarioPrompt> listPrompts(String username, String projectName, String versionLabel) {
        return repo.findByUsernameAndProjectNameAndVersionLabelOrderByCreatedAtDesc(username, projectName, versionLabel);
    }

    public void clearPrompts(String username, String projectName, String versionLabel) {
        repo.deleteByUsernameAndProjectNameAndVersionLabel(username, projectName, versionLabel);
    }

    /** For LLM history use */
    public String buildChatHistory(String username, String projectName, String versionLabel, int maxMessages, int maxChars) {
        List<UnitTestScenarioPrompt> recent = listPrompts(username, projectName, versionLabel);
        if (recent.isEmpty()) return "";

        List<UnitTestScenarioPrompt> limited = recent.size() > maxMessages
                ? new ArrayList<>(recent.subList(0, maxMessages))
                : recent;

        StringBuilder sb = new StringBuilder();
        for (int i = limited.size() - 1; i >= 0; i--) {
            UnitTestScenarioPrompt p = limited.get(i);
            String body = p.getMessage() == null ? "" : p.getMessage().replace("\n", " ").trim();
            String line = "- " + body + " (runId:" + p.getRunId() + ")";
            if (sb.length() + line.length() > maxChars) break;
            if (sb.length() > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString();
    }
}

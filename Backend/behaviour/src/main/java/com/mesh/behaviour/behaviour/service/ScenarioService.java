package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.model.ScenarioPrompt;
import com.mesh.behaviour.behaviour.repository.ScenarioPromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Stores and retrieves chat-style scenario prompts (user feedback) per project.
 * Also builds a compact chat history string for LLM prompts.
 */
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioPromptRepository repo;

    public ScenarioPrompt savePrompt(String username, String projectName, String message, Long runId) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(projectName) || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException("username, projectName and message are required");
        }
        ScenarioPrompt sp = ScenarioPrompt.builder()
                .username(username.trim())
                .projectName(projectName.trim())
                .message(message.trim())
                .runId(runId)
                .createdAt(new Timestamp(System.currentTimeMillis()))
                .build();
        return repo.save(sp);
    }

    /** All prompts for a project, newest first. */
    public List<ScenarioPrompt> listPrompts(String username, String projectName) {
        return repo.findByUsernameAndProjectNameOrderByCreatedAtDesc(username, projectName);
    }

    /** Recent N prompts for a project, newest first (best-effort cap). */
    public List<ScenarioPrompt> listPrompts(String username, String projectName, int maxCount) {
        List<ScenarioPrompt> all = listPrompts(username, projectName);
        if (maxCount <= 0 || all.size() <= maxCount) return all;
        return new ArrayList<>(all.subList(0, maxCount));
    }

    /**
     * Build a compact chat history string for an LLM.
     * - Takes the most recent maxMessages prompts
     * - Truncates overall length to maxChars
     */
    public String buildChatHistory(String username, String projectName, int maxMessages, int maxChars) {
        List<ScenarioPrompt> recent = listPrompts(username, projectName, Math.max(1, maxMessages));
        StringBuilder sb = new StringBuilder();
        // reverse to oldest→newest for better LLM context flow
        for (int i = recent.size() - 1; i >= 0; i--) {
            ScenarioPrompt p = recent.get(i);
            String line = "- " + p.getMessage().replace("\n", " ").trim();
            if (sb.length() + line.length() + 1 > maxChars) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    /** Delete ALL prompts for a project (admin/cleanup). */
    public void clearPrompts(String username, String projectName) {
        var all = listPrompts(username, projectName);
        if (!all.isEmpty()) {
            repo.deleteAllInBatch(all);
        }
    }
}

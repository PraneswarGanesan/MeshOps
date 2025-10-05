package com.mesh.behaviour.behaviour.service;

import com.mesh.behaviour.behaviour.model.ScenarioPrompt;
import com.mesh.behaviour.behaviour.repository.ScenarioPromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioPromptRepository repo;

    private static final int MAX_MESSAGE_CHARS = 2000;

    /** Save a user prompt. runId may be null. */
    public ScenarioPrompt savePrompt(String username, String projectName, String versionLabel, String message, Long runId) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - ScenarioService cannot create versions");
        }
        return savePromptInternal(username, projectName, versionLabel, message, runId, "user");
    }

    /** Save a system prompt (automated hint from platform). */
    public ScenarioPrompt saveSystemPrompt(String username, String projectName, String versionLabel, String message, Long runId) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - ScenarioService cannot create versions");
        }
        return savePromptInternal(username, projectName, versionLabel, message, runId, "system");
    }

    private ScenarioPrompt savePromptInternal(String username, String projectName, String versionLabel,
                                              String message, Long runId, String role) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(projectName) || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException("username, projectName and message are required");
        }

        String trimmed = message.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_CHARS - 16) + " ...[truncated]";
        }

        String storeMessage = "[" + role + "] " + trimmed;
        // ✅ Auto-generate small runId if missing
        Long effectiveRunId = (runId != null)
                ? runId
                : ThreadLocalRandom.current().nextLong(100, 1000); // 3-digit number

        ScenarioPrompt sp = ScenarioPrompt.builder()
                .username(username.trim())
                .projectName(projectName.trim())
                .versionLabel(versionLabel.trim())
                .message(storeMessage)
                .runId(effectiveRunId)
                .createdAt(new Timestamp(System.currentTimeMillis()))
                .build();
        return repo.save(sp);
    }

    /** All prompts for a project+version, newest first. */
    public List<ScenarioPrompt> listPrompts(String username, String projectName, String versionLabel) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - ScenarioService cannot list prompts");
        }
        return repo.findByUsernameAndProjectNameAndVersionLabelOrderByCreatedAtDesc(username, projectName, versionLabel);
    }

    /** Recent N prompts for a project+version. */
    public List<ScenarioPrompt> listPrompts(String username, String projectName, String versionLabel, int maxCount) {
        List<ScenarioPrompt> all = listPrompts(username, projectName, versionLabel);
        if (maxCount <= 0 || all.size() <= maxCount) return all;
        return new ArrayList<>(all.subList(0, maxCount));
    }

    /** Clear prompts for a specific version */
    public void clearPrompts(String username, String projectName, String versionLabel) {
        if (!StringUtils.hasText(versionLabel)) {
            throw new IllegalArgumentException("versionLabel is required - ScenarioService cannot clear prompts");
        }
        repo.deleteByUsernameAndProjectNameAndVersionLabel(username, projectName, versionLabel);
    }

    /** Build compact chat history for LLM. */
    public String buildChatHistory(String username, String projectName, String versionLabel,
                                   int maxMessages, int maxChars) {
        List<ScenarioPrompt> recent = listPrompts(username, projectName, versionLabel, Math.max(1, maxMessages));
        StringBuilder sb = new StringBuilder();
        for (int i = recent.size() - 1; i >= 0; i--) {
            ScenarioPrompt p = recent.get(i);
            String raw = p.getMessage() == null ? "" : p.getMessage().replace("\n", " ").trim();
            String role = extractRolePrefix(raw);
            String body = stripRolePrefix(raw);

            String age = relativeAge(p.getCreatedAt());
            String runPart = p.getRunId() == null ? "" : " (runId:" + p.getRunId() + ")";
            String line = "- [" + role + "] " + body + runPart + ", " + age;

            if (sb.length() + line.length() + 1 > maxChars) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.toString();
    }

    // ---------------- helpers ----------------

    private String extractRolePrefix(String message) {
        if (!StringUtils.hasText(message)) return "user";
        if (message.startsWith("[") && message.contains("]")) {
            int idx = message.indexOf(']');
            String r = message.substring(1, idx).trim();
            return r.isEmpty() ? "user" : r;
        }
        return "user";
    }

    private String stripRolePrefix(String message) {
        if (!StringUtils.hasText(message)) return "";
        if (message.startsWith("[") && message.contains("]")) {
            int idx = message.indexOf(']');
            return message.substring(idx + 1).trim();
        }
        return message;
    }

    private String relativeAge(Timestamp ts) {
        if (ts == null) return "unknown";
        long secs = Duration.between(ts.toInstant(), Instant.now()).getSeconds();
        if (secs < 60) return "just now";
        if (secs < 3600) return (secs / 60) + "m ago";
        if (secs < 86400) return (secs / 3600) + "h ago";
        return (secs / 86400) + "d ago";
    }
}

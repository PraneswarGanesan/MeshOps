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

/**
 * Stores and retrieves chat-style scenario prompts (user feedback) per project.
 * Also builds a compact chat history string for LLM prompts.
 *
 * Improvements:
 *  - Max message length enforcement
 *  - Optional 'source' (user/system) stored as a short prefix in the message (no DB schema change)
 *  - Richer chat history formatting (includes runId and relative age, still compact)
 */
@Service
@RequiredArgsConstructor
public class ScenarioService {

    private final ScenarioPromptRepository repo;

    // sane cap for each prompt to protect DB & LLM tokens
    private static final int MAX_MESSAGE_CHARS = 2000;

    /**
     * Save a user prompt. runId may be null.
     */
    public ScenarioPrompt savePrompt(String username, String projectName, String message, Long runId) {
        return savePromptInternal(username, projectName, message, runId, "user");
    }

    /**
     * Save a system prompt (automated hint from platform).
     */
    public ScenarioPrompt saveSystemPrompt(String username, String projectName, String message, Long runId) {
        return savePromptInternal(username, projectName, message, runId, "system");
    }

    /**
     * Internal saver that tags message with a short role prefix (avoids DB schema change).
     */
    private ScenarioPrompt savePromptInternal(String username, String projectName, String message, Long runId, String role) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(projectName) || !StringUtils.hasText(message)) {
            throw new IllegalArgumentException("username, projectName and message are required");
        }

        String trimmed = message.trim();
        if (trimmed.length() > MAX_MESSAGE_CHARS) {
            trimmed = trimmed.substring(0, MAX_MESSAGE_CHARS - 16) + " ...[truncated]";
        }

        // store short role prefix in message to avoid DB migration while keeping role metadata
        String storeMessage = "[" + role + "] " + trimmed;

        ScenarioPrompt sp = ScenarioPrompt.builder()
                .username(username.trim())
                .projectName(projectName.trim())
                .message(storeMessage)
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
     * - Formats each line as: "- [role] message (runId:NN, 2h ago)"
     *
     * Example:
     * - [user] Please add more scenarios about international transactions. (runId:42, 2h ago)
     *
     * This keeps LLM context useful yet concise.
     */
    public String buildChatHistory(String username, String projectName, int maxMessages, int maxChars) {
        List<ScenarioPrompt> recent = listPrompts(username, projectName, Math.max(1, maxMessages));
        StringBuilder sb = new StringBuilder();
        // reverse to oldest→newest for better LLM context flow
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

    /** Delete ALL prompts for a project (admin/cleanup). */
    public void clearPrompts(String username, String projectName) {
        var all = listPrompts(username, projectName);
        if (!all.isEmpty()) {
            repo.deleteAllInBatch(all);
        }
    }

    // ---------------- helpers ----------------

    /** Extract role if stored as [role] prefix; default "user". */
    private String extractRolePrefix(String message) {
        if (!StringUtils.hasText(message)) return "user";
        if (message.startsWith("[") && message.contains("]")) {
            int idx = message.indexOf(']');
            String r = message.substring(1, idx).trim();
            return r.isEmpty() ? "user" : r;
        }
        return "user";
    }

    /** Remove the role prefix for LLM content (keeps message body). */
    private String stripRolePrefix(String message) {
        if (!StringUtils.hasText(message)) return "";
        if (message.startsWith("[") && message.contains("]")) {
            int idx = message.indexOf(']');
            return message.substring(idx + 1).trim();
        }
        return message;
    }

    /** Human-friendly relative age (e.g., "2h ago", "5m ago", "just now"). */
    private String relativeAge(Timestamp ts) {
        if (ts == null) return "unknown";
        long secs = Duration.between(ts.toInstant(), Instant.now()).getSeconds();
        if (secs < 60) return "just now";
        if (secs < 3600) return (secs / 60) + "m ago";
        if (secs < 86400) return (secs / 3600) + "h ago";
        return (secs / 86400) + "d ago";
    }
}

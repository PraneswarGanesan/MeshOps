// src/main/java/com/mesh/behaviour/behaviour/controller/ScenarioController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.model.ScenarioPrompt;
import com.mesh.behaviour.behaviour.service.ScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS})
public class ScenarioController {

    private final ScenarioService scenarioService;

    /**
     * Frontend expects:
     * POST /api/scenarios/{username}/{projectName}/prompts
     * Body: { "message": "...", "runId": 42 }
     */
    @PostMapping("/{username}/{projectName}/prompts")
    public ScenarioPrompt savePrompt(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody Map<String, Object> body
    ) {
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        Long runId = null;
        if (body != null && body.get("runId") != null) {
            try { runId = Long.valueOf(String.valueOf(body.get("runId"))); } catch (Exception ignored) {}
        }
        return scenarioService.savePrompt(username, projectName, message, runId);
    }

    /**
     * Frontend expects:
     * GET /api/scenarios/{username}/{projectName}/prompts?limit=N
     */
    @GetMapping("/{username}/{projectName}/prompts")
    public List<ScenarioPrompt> listPrompts(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        if (limit != null && limit > 0) {
            return scenarioService.listPrompts(username, projectName, limit);
        }
        return scenarioService.listPrompts(username, projectName);
    }

    /**
     * Optional cleanup:
     * DELETE /api/scenarios/{username}/{projectName}/prompts
     */
    @DeleteMapping("/{username}/{projectName}/prompts")
    public Map<String, Object> clearPrompts(
            @PathVariable String username,
            @PathVariable String projectName
    ) {
        scenarioService.clearPrompts(username, projectName);
        return Map.of("cleared", true);
    }
}

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
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000"}, 
             allowedHeaders = "*", 
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowCredentials = "true")
public class ScenarioController {

    private final ScenarioService scenarioService;

    /**
     * Frontend expects:
     * POST /api/scenarios/{username}/{projectName}/{versionLabel}/prompts
     * Body: { "message": "...", "runId": 42 }
     */
    @PostMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public ScenarioPrompt savePrompt(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestBody Map<String, Object> body
    ) {
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        Long runId = null;
        if (body != null && body.get("runId") != null) {
            try { runId = Long.valueOf(String.valueOf(body.get("runId"))); } catch (Exception ignored) {}
        }
        return scenarioService.savePrompt(username, projectName, versionLabel, message, runId);
    }

    /**
     * Frontend expects:
     * GET /api/scenarios/{username}/{projectName}/{versionLabel}/prompts?limit=N
     */
    @GetMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public List<ScenarioPrompt> listPrompts(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        if (limit != null && limit > 0) {
            return scenarioService.listPrompts(username, projectName, versionLabel, limit);
        }
        return scenarioService.listPrompts(username, projectName, versionLabel);
    }

    /**
     * Optional cleanup:
     * DELETE /api/scenarios/{username}/{projectName}/{versionLabel}/prompts
     */
    @DeleteMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public Map<String, Object> clearPrompts(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel
    ) {
        scenarioService.clearPrompts(username, projectName, versionLabel);
        return Map.of("cleared", true);
    }
}

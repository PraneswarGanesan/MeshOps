// src/main/java/com/mesh/behaviour/behaviour/controller/UnitTestScenarioController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.model.UnitTestScenarioPrompt;
import com.mesh.behaviour.behaviour.service.UnitTestScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/unit-scenarios")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000", "http://127.0.0.1:5173", "http://127.0.0.1:3000"}, 
             allowedHeaders = "*", 
             methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.DELETE, RequestMethod.OPTIONS},
             allowCredentials = "true")
public class UnitTestScenarioController {

    private final UnitTestScenarioService unitScenarioService;

    /**
     * POST /api/unit-scenarios/{username}/{projectName}/{versionLabel}/prompts
     * Body: { "message": "...", "runId": 42 }
     */
    @PostMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public UnitTestScenarioPrompt savePrompt(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestBody Map<String, Object> body
    ) {
        String message = body == null ? null : String.valueOf(body.getOrDefault("message", ""));
        Long runId = null;
        if (body != null && body.get("runId") != null) {
            try {
                runId = Long.valueOf(String.valueOf(body.get("runId")));
            } catch (Exception ignored) {}
        }
        return unitScenarioService.savePrompt(username, projectName, versionLabel, message, runId);
    }

    /**
     * GET /api/unit-scenarios/{username}/{projectName}/{versionLabel}/prompts?limit=N
     */
    @GetMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public List<UnitTestScenarioPrompt> listPrompts(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestParam(name = "limit", required = false) Integer limit
    ) {
        List<UnitTestScenarioPrompt> all = unitScenarioService.listPrompts(username, projectName, versionLabel);
        if (limit != null && limit > 0 && all.size() > limit) {
            return all.subList(0, limit);
        }
        return all;
    }

    /**
     * DELETE /api/unit-scenarios/{username}/{projectName}/{versionLabel}/prompts
     */
    @DeleteMapping("/{username}/{projectName}/{versionLabel}/prompts")
    public Map<String, Object> clearPrompts(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel
    ) {
        unitScenarioService.clearPrompts(username, projectName, versionLabel);
        return Map.of("cleared", true);
    }
}

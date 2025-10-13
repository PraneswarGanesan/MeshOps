// src/main/java/com/mesh/behaviour/behaviour/controller/PlanController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.dto.*;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class PlanController {

    private final PlanService planService;

    // Generate initial plan (driver.py + tests.yaml) for specific version
    @PostMapping("/{username}/{projectName}/{versionLabel}/generate")
    public Mono<Map<String, String>> generateInitialPlan(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestBody GeneratePlanRequest req) {
        return planService.generateAndSave(username, projectName, versionLabel, req);
    }

    // List canonical + all versioned testcases under /tests/ in that version folder
    @GetMapping("/{username}/{projectName}/{versionLabel}/tests")
    public Map<String, Object> listAllTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel) {
        return planService.listAllTests(username, projectName, versionLabel);
    }

    // Generate a new tests.yaml (inside version folder). Driver is not touched.
    @PostMapping("/{username}/{projectName}/{versionLabel}/tests/new")
    public Map<String, String> generateNewTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestBody GenerateTestsRequest req) {
        return planService.generateNewTests(username, projectName, versionLabel, req);
    }

    // Activate a chosen tests file as canonical (inside version folder)
    @PostMapping("/{username}/{projectName}/{versionLabel}/tests/activate")
    public Map<String, String> activateTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestBody ActivateTestsRequest req) {
        return planService.activateTests(username, projectName, versionLabel, req);
    }

    // Approve canonical driver/tests pair
    @PostMapping("/approve")
    public Project approvePlan(@RequestBody ApprovePlanRequest req) {
        return planService.approvePlan(req);
    }
}

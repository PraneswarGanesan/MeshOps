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
@CrossOrigin(origins = "*")
public class PlanController {

    private final PlanService planService;

    @PostMapping("/{username}/{projectName}/generate")
    public Mono<Map<String, String>> generateAndSave(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody GeneratePlanRequest req) {
        return planService.generateAndSave(username, projectName, req);
    }

    // NEW: list canonical + all versioned testcases
    @GetMapping("/{username}/{projectName}/tests")
    public Map<String, Object> listAllTests(
            @PathVariable String username,
            @PathVariable String projectName) {
        return planService.listAllTests(username, projectName);
    }

    // NEW: generate a fresh tests.yaml (versioned). Does NOT touch driver.py.
    @PostMapping("/{username}/{projectName}/tests/new")
    public Map<String, String> generateNewTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody GenerateTestsRequest req) {
        return planService.generateNewTests(username, projectName, req);
    }

    // NEW: activate a chosen tests file as canonical tests.yaml
    @PostMapping("/{username}/{projectName}/tests/activate")
    public Map<String, String> activateTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody ActivateTestsRequest req) {
        return planService.activateTests(username, projectName, req);
    }

    @PostMapping("/approve")
    public Project approvePlan(@RequestBody ApprovePlanRequest req) {
        return planService.approvePlan(req);
    }
}

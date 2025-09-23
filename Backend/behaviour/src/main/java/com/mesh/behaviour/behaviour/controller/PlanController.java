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

    // Generate initial plan (driver.py + tests.yaml) in pre-processed/ + canonical
    @PostMapping("/{username}/{projectName}/generate")
    public Mono<Map<String, String>> generateInitialPlan(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody GeneratePlanRequest req) {
        return planService.generateAndSave(username, projectName, req);
    }

    // List canonical + all versioned testcases under /tests/
    @GetMapping("/{username}/{projectName}/tests")
    public Map<String, Object> listAllTests(
            @PathVariable String username,
            @PathVariable String projectName) {
        return planService.listAllTests(username, projectName);
    }

    // Generate a new tests.yaml (versioned). Driver is not touched.
    @PostMapping("/{username}/{projectName}/tests/new")
    public Map<String, String> generateNewTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody GenerateTestsRequest req) {
        return planService.generateNewTests(username, projectName, req);
    }

    // Activate a chosen tests file as canonical
    @PostMapping("/{username}/{projectName}/tests/activate")
    public Map<String, String> activateTests(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody ActivateTestsRequest req) {
        return planService.activateTests(username, projectName, req);
    }

    // Approve canonical driver/tests pair
    @PostMapping("/approve")
    public Project approvePlan(@RequestBody ApprovePlanRequest req) {
        return planService.approvePlan(req);
    }
}

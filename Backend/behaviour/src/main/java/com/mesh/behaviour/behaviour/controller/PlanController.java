package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.dto.ApprovePlanRequest;
import com.mesh.behaviour.behaviour.dto.GeneratePlanRequest;
import com.mesh.behaviour.behaviour.model.Project;
import com.mesh.behaviour.behaviour.service.PlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    // STEP 1: Generate with LLM → save to S3 → return file keys + contents to frontend
    @PostMapping("/{username}/{projectName}/generate")
    public Mono<Map<String, String>> generateAndSave(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestBody GeneratePlanRequest req) {
        return planService.generateAndSave(username, projectName, req);
    }

    // STEP 2: User approves or uploads revised files → overwrite in S3 + mark approved
    @PostMapping("/approve")
    public Project approvePlan(@RequestBody ApprovePlanRequest req) {
        return planService.approvePlan(req);
    }
}

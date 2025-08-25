package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.dto.ArtifactView;
import com.mesh.behaviour.behaviour.dto.RunStatusView;
import com.mesh.behaviour.behaviour.dto.StartRunRequest;
import com.mesh.behaviour.behaviour.service.RunService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;

    @PostMapping("/start")
    public RunStatusView startRun(@RequestBody StartRunRequest req) {
        return runService.startRun(req);
    }

    @GetMapping("/{runId}/status")
    public RunStatusView getStatus(@PathVariable Long runId) {
        return runService.getStatus(runId);
    }

    @PostMapping("/{runId}/poll")
    public RunStatusView pollAndUpdate(@PathVariable Long runId) {
        return runService.pollAndUpdate(runId);
    }

    @GetMapping("/{runId}/artifacts")
    public List<ArtifactView> listArtifacts(@PathVariable Long runId) {
        return runService.listArtifacts(runId);
    }
}

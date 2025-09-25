package com.mesh.unittest.controller;

import com.mesh.unittest.dto.GenerateTestsRequest;
import com.mesh.unittest.dto.RunStatusResponse;
import com.mesh.unittest.dto.StartRunRequest;
import com.mesh.unittest.service.StreamingService;
import com.mesh.unittest.service.TestGenerationService;
import com.mesh.unittest.service.UnitTestRunService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/unit")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@Slf4j
public class UnitTestController {

    private final TestGenerationService testGenerationService;
    private final UnitTestRunService runService;
    private final StreamingService streamingService;

    /**
     * Generate unit tests for a project version
     * POST /api/unit/{username}/{project}/{version}/generate
     */
    @PostMapping("/{username}/{project}/{version}/generate")
    public ResponseEntity<Map<String, Object>> generateTests(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @RequestBody(required = false) GenerateTestsRequest request) {
        
        log.info("Generating unit tests for {}/{}/{}", username, project, version);
        
        String userFeedback = request != null ? request.getUserFeedback() : null;
        String testType = request != null ? request.getTestType() : "unit";
        Integer testCount = request != null ? request.getTestCount() : null;
        
        Map<String, Object> result = testGenerationService.generateUnitTests(
                username, project, version, userFeedback, testType, testCount);
        
        return ResponseEntity.ok(result);
    }

    /**
     * Start a unit test run
     * POST /api/unit/{username}/{project}/{version}/run
     */
    @PostMapping("/{username}/{project}/{version}/run")
    public ResponseEntity<RunStatusResponse> startRun(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @Valid @RequestBody StartRunRequest request) {
        
        log.info("Starting unit test run for {}/{}/{}", username, project, version);
        
        RunStatusResponse response = runService.startRun(username, project, version, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get all runs for a project version
     * GET /api/unit/{username}/{project}/{version}/runs
     */
    @GetMapping("/{username}/{project}/{version}/runs")
    public ResponseEntity<List<RunStatusResponse>> getRuns(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version) {
        
        List<RunStatusResponse> runs = runService.getRuns(username, project, version);
        return ResponseEntity.ok(runs);
    }

    /**
     * Get specific run status
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}
     */
    @GetMapping("/{username}/{project}/{version}/runs/{runId}")
    public ResponseEntity<RunStatusResponse> getRun(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        return runService.getRun(username, project, version, runId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Stream logs in real-time
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}/logs/stream
     */
    @GetMapping(value = "/{username}/{project}/{version}/runs/{runId}/logs/stream", 
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamLogs(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        log.info("Starting log stream for run {} in {}/{}/{}", runId, username, project, version);
        
        return streamingService.streamLogs(username, project, version, runId);
    }

    /**
     * Get static logs
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}/logs
     */
    @GetMapping("/{username}/{project}/{version}/runs/{runId}/logs")
    public ResponseEntity<String> getLogs(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        String logs = runService.getRunLogs(username, project, version, runId);
        return ResponseEntity.ok(logs);
    }

    /**
     * Stream metrics in real-time
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}/metrics/live
     */
    @GetMapping(value = "/{username}/{project}/{version}/runs/{runId}/metrics/live", 
                produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMetrics(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        log.info("Starting metrics stream for run {} in {}/{}/{}", runId, username, project, version);
        
        return streamingService.streamMetrics(username, project, version, runId);
    }

    /**
     * Get static metrics
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}/metrics
     */
    @GetMapping("/{username}/{project}/{version}/runs/{runId}/metrics")
    public ResponseEntity<String> getMetrics(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        String metrics = runService.getRunMetrics(username, project, version, runId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(metrics);
    }

    /**
     * Get graph artifacts
     * GET /api/unit/{username}/{project}/{version}/runs/{runId}/graphs
     */
    @GetMapping("/{username}/{project}/{version}/runs/{runId}/graphs")
    public Mono<ResponseEntity<String>> getGraphs(
            @PathVariable String username,
            @PathVariable String project,
            @PathVariable String version,
            @PathVariable Long runId) {
        
        return streamingService.getGraphUrls(username, project, version, runId)
                .map(urls -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(urls));
    }

    /**
     * Health check endpoint
     * GET /api/unit/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "unit-test-service",
                "timestamp", System.currentTimeMillis()
        ));
    }
}

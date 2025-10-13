// src/main/java/com/mesh/behaviour/behaviour/controller/UnitTestController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.service.UnitTestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/unit-tests")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:5173", allowCredentials = "true")
public class UnitTestController {

    private final UnitTestService unitTestService;

    /**
     * POST /api/unit-tests/{username}/{projectName}/{versionLabel}/refine?runId=NN&autoRun=true
     * Body: { "userFeedback": "text..." }  // optional
     */
    @PostMapping("/{username}/{projectName}/{versionLabel}/refine")
    public Map<String, Object> refineAndActivateUnit(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestParam("runId") long runId,
            @RequestParam(name = "autoRun", defaultValue = "false") boolean autoRun,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String feedback = null;
        if (body != null && body.get("userFeedback") != null) {
            feedback = String.valueOf(body.get("userFeedback"));
        }
        return unitTestService.refineAndActivateUnitTests(username, projectName, versionLabel, runId, feedback, autoRun);
    }
}

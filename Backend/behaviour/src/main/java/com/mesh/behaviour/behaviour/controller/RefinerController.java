// src/main/java/com/mesh/behaviour/behaviour/controller/RefinerController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.service.RefinerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/refiner")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class RefinerController {

    private final RefinerService refinerService;

    /**
     * Frontend expects:
     * POST /api/refiner/{username}/{projectName}/refine?runId=NN&autoRun=true
     * Body: { "userFeedback": "text..." }  // optional
     * Returns: { versionKey, canonicalKey, activated, newRunId? }
     */
    @PostMapping("/{username}/{projectName}/refine")
    public Map<String, Object> refineAndActivate(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestParam("runId") long runId,
            @RequestParam(name = "autoRun", defaultValue = "false") boolean autoRun,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        String feedback = null;
        if (body != null && body.get("userFeedback") != null) {
            feedback = String.valueOf(body.get("userFeedback"));
        }
        return refinerService.refineAndActivateTests(username, projectName, runId, feedback, autoRun);
    }
}

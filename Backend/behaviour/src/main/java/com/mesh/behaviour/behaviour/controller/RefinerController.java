// src/main/java/com/mesh/behaviour/behaviour/controller/RefinerController.java
package com.mesh.behaviour.behaviour.controller;

import com.mesh.behaviour.behaviour.service.RefinerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/refiner")
@RequiredArgsConstructor
@CrossOrigin(origins = "*", allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
public class RefinerController {

    private final RefinerService refinerService;

    /**
     * Frontend expects:
     * POST /api/refiner/{username}/{projectName}/{versionLabel}/refine?promptId=299&autoRun=true
     * Body: { "userFeedback": "text..." }  // optional
     * Returns: { versionKey, canonicalKey, activated, newRunId? }
     */
    @PostMapping("/{username}/{projectName}/{versionLabel}/refine")
    public Map<String, Object> refineAndActivate(
            @PathVariable String username,
            @PathVariable String projectName,
            @PathVariable String versionLabel,
            @RequestParam("promptId") long promptId,
            @RequestParam(name = "autoRun", defaultValue = "false") boolean autoRun,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        // Optional feedback text passed from frontend
        String feedback = null;
        if (body != null && body.get("userFeedback") != null) {
            feedback = String.valueOf(body.get("userFeedback"));
        }

        // Call service using promptId instead of runId
        return refinerService.refineAndActivateByPrompt(
                username, projectName, versionLabel, promptId, autoRun
        );
    }
}

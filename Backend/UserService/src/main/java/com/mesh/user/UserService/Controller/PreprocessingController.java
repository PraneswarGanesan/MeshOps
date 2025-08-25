package com.mesh.user.UserService.Controller;

import com.mesh.user.UserService.Service.PreprocessingService;
import com.mesh.user.UserService.Service.PreprocessingServiceEC2;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/preprocessing")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PreprocessingController {

    private final PreprocessingService preprocessingService;
    private final PreprocessingServiceEC2 preprocessingServiceEC2;

    /**
     * Trigger preprocessing for selected files in a folder (or entire folder if files=[]).
     * Body: JSON array of file names relative to folder (e.g. ["data.csv","images/img1.png"])
     * If you want Lambda to process everything under folder, send an empty array [] or omit body.
     *
     * Example:
     * POST /api/preprocessing/neo_cyberpunk/projects/my-awesome-project/trigger?folder=raw-data
     * Body: ["data.csv","img/sample.png"]
     */
    @PostMapping("/{username}/projects/{projectName}/trigger")
    public ResponseEntity<String> trigger(
            @PathVariable String username,
            @PathVariable String projectName,
            @RequestParam(value = "folder", required = false) String folder,
            @RequestBody(required = false) List<String> files) {

        // files can be null; service/Lambda will auto-discover if null/empty
        String resultJson = preprocessingServiceEC2.trigger(username, projectName, folder, files);
        return ResponseEntity.ok(resultJson);
    }
}

package com.mesh.unittest.service;

import com.mesh.unittest.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TestGenerationService {

    private final S3Service s3Service;

    public Map<String, Object> generateUnitTests(String username, String projectName, String version, 
                                                String userFeedback, String testType, Integer testCount) {
        
        String versionPath = S3KeyUtil.buildVersionPath(username, projectName, version);
        String canonicalTestsKey = S3KeyUtil.join(versionPath, "tests.yaml");
        
        // Archive existing tests.yaml if it exists
        if (s3Service.exists(canonicalTestsKey)) {
            String existingTests = s3Service.getString(canonicalTestsKey);
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String archiveKey = S3KeyUtil.buildUnitTestArchivePath(username, projectName, version, timestamp);
            s3Service.putString(archiveKey, existingTests, "text/yaml");
            log.info("Archived existing tests.yaml to: {}", archiveKey);
        }
        
        // Generate new unit tests based on project context
        String newTests = generateTestsContent(username, projectName, version, userFeedback, testType, testCount);
        
        // Save new tests as canonical
        s3Service.putString(canonicalTestsKey, newTests, "text/yaml");
        log.info("Generated new unit tests for {}/{}/{}", username, projectName, version);
        
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("canonicalKey", canonicalTestsKey);
        result.put("testType", testType);
        result.put("testCount", countTestsInYaml(newTests));
        result.put("generated", true);
        result.put("timestamp", System.currentTimeMillis());
        
        return result;
    }

    private String generateTestsContent(String username, String projectName, String version, 
                                      String userFeedback, String testType, Integer testCount) {
        
        // Read project context
        String versionPath = S3KeyUtil.buildVersionPath(username, projectName, version);
        String driverKey = S3KeyUtil.join(versionPath, "driver.py");
        String manifestKey = S3KeyUtil.join(versionPath, "manifest.json");
        
        String driverContent = s3Service.exists(driverKey) ? 
            s3Service.getStringSafe(driverKey, 5000, 100) : "";
        String manifestContent = s3Service.exists(manifestKey) ? 
            s3Service.getStringSafe(manifestKey, 2000, 50) : "";
        
        // Determine test count
        int numTests = testCount != null ? Math.min(testCount, 20) : 5;
        
        // Generate unit tests based on project type
        StringBuilder testsYaml = new StringBuilder();
        testsYaml.append("tests:\n");
        testsYaml.append("  type: unit\n");
        testsYaml.append("  scenarios:\n");
        
        // Detect project type from driver content
        boolean isImageProject = driverContent.toLowerCase().contains("image") || 
                                driverContent.toLowerCase().contains("cv2") ||
                                driverContent.toLowerCase().contains("pillow");
        boolean isTextProject = driverContent.toLowerCase().contains("text") || 
                               driverContent.toLowerCase().contains("nlp") ||
                               driverContent.toLowerCase().contains("tokenizer");
        
        for (int i = 1; i <= numTests; i++) {
            testsYaml.append("    - name: unit_test_").append(i).append("\n");
            testsYaml.append("      description: Unit test case ").append(i).append("\n");
            
            if (isImageProject) {
                testsYaml.append("      input:\n");
                testsYaml.append("        image_path: \"images/val/sample_").append(i).append(".jpg\"\n");
                testsYaml.append("        batch_size: 1\n");
            } else if (isTextProject) {
                testsYaml.append("      input:\n");
                testsYaml.append("        text: \"Unit test sample text ").append(i).append("\"\n");
                testsYaml.append("        max_length: 512\n");
            } else {
                // Tabular data
                testsYaml.append("      input:\n");
                testsYaml.append("        feature_1: ").append(i * 10).append("\n");
                testsYaml.append("        feature_2: ").append(i * 5.5).append("\n");
                testsYaml.append("        feature_3: ").append(i % 2).append("\n");
            }
            
            testsYaml.append("      expected:\n");
            testsYaml.append("        type: \"unit_validation\"\n");
            testsYaml.append("        min_confidence: 0.7\n");
            testsYaml.append("        max_latency_ms: 1000\n");
            
            if (userFeedback != null && !userFeedback.trim().isEmpty()) {
                testsYaml.append("      notes: \"").append(userFeedback.trim()).append("\"\n");
            }
        }
        
        return testsYaml.toString();
    }
    
    private int countTestsInYaml(String yamlContent) {
        if (yamlContent == null) return 0;
        return (int) yamlContent.lines()
                .filter(line -> line.trim().startsWith("- name:"))
                .count();
    }
}

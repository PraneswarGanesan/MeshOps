package com.mesh.unittest.dto;

import lombok.Data;

@Data
public class GenerateTestsRequest {
    
    private String userFeedback;
    private String testType = "unit";
    private Integer testCount;
    private String focusArea;
}

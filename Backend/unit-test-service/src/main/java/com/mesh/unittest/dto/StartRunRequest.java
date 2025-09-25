package com.mesh.unittest.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class StartRunRequest {
    
    @NotBlank(message = "Task is required")
    private String task = "classification";
    
    private String description;
}

package com.mesh.behaviour.behaviour.dto;

import lombok.*;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GeneratePlanRequest {
    private String brief;
    // file names relative to pre-processed base (e.g. "train.py", "predict.py", "requirements.txt", "data/dataset.csv")
    private List<String> files;
}

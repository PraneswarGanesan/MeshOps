package com.mesh.unittest.service;

import com.mesh.unittest.util.S3KeyUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class StreamingService {

    private final S3Service s3Service;

    public Flux<String> streamLogs(String username, String projectName, String version, Long runId) {
        String logsKey = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "logs.txt"
        );
        
        AtomicLong lastPosition = new AtomicLong(0);
        
        return Flux.interval(Duration.ofSeconds(2))
                .flatMap(tick -> {
                    try {
                        if (!s3Service.exists(logsKey)) {
                            return Mono.just("data: {\"status\": \"waiting\", \"message\": \"Logs not available yet\"}\n\n");
                        }
                        
                        String fullContent = s3Service.getString(logsKey);
                        long currentLength = fullContent.length();
                        long lastPos = lastPosition.get();
                        
                        if (currentLength > lastPos) {
                            String newContent = fullContent.substring((int) lastPos);
                            lastPosition.set(currentLength);
                            
                            // Format as Server-Sent Events
                            String[] lines = newContent.split("\n");
                            StringBuilder sseData = new StringBuilder();
                            
                            for (String line : lines) {
                                if (!line.trim().isEmpty()) {
                                    sseData.append("data: {\"type\": \"log\", \"content\": \"")
                                           .append(line.replace("\"", "\\\""))
                                           .append("\"}\n\n");
                                }
                            }
                            
                            return Mono.just(sseData.toString());
                        }
                        
                        return Mono.just("data: {\"type\": \"heartbeat\"}\n\n");
                        
                    } catch (Exception e) {
                        log.error("Error streaming logs for run {}", runId, e);
                        return Mono.just("data: {\"type\": \"error\", \"message\": \"" + e.getMessage() + "\"}\n\n");
                    }
                })
                .takeUntil(data -> data.contains("\"status\": \"completed\"") || data.contains("\"status\": \"failed\""));
    }

    public Flux<String> streamMetrics(String username, String projectName, String version, Long runId) {
        String metricsKey = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "partial_metrics.json"
        );
        
        return Flux.interval(Duration.ofSeconds(3))
                .flatMap(tick -> {
                    try {
                        String content;
                        
                        // Try partial metrics first
                        if (s3Service.exists(metricsKey)) {
                            content = s3Service.getString(metricsKey);
                        } else {
                            // Try final metrics
                            String finalMetricsKey = S3KeyUtil.join(
                                    S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                                    "metrics.json"
                            );
                            
                            if (s3Service.exists(finalMetricsKey)) {
                                content = s3Service.getString(finalMetricsKey);
                            } else {
                                content = "{\"status\": \"waiting\", \"message\": \"Metrics not available yet\"}";
                            }
                        }
                        
                        return Mono.just("data: " + content + "\n\n");
                        
                    } catch (Exception e) {
                        log.error("Error streaming metrics for run {}", runId, e);
                        return Mono.just("data: {\"type\": \"error\", \"message\": \"" + e.getMessage() + "\"}\n\n");
                    }
                })
                .takeUntil(data -> data.contains("\"status\": \"completed\"") || data.contains("\"status\": \"failed\""));
    }

    public Mono<String> getGraphUrls(String username, String projectName, String version, Long runId) {
        String graphsPath = S3KeyUtil.join(
                S3KeyUtil.buildUnitRunPath(username, projectName, version, runId),
                "graphs"
        );
        
        return Mono.fromCallable(() -> {
            StringBuilder result = new StringBuilder();
            result.append("{\"graphs\": {");
            
            String[] graphFiles = {"loss_curve.png", "accuracy_curve.png", "confusion_matrix.png"};
            boolean first = true;
            
            for (String graphFile : graphFiles) {
                String graphKey = S3KeyUtil.join(graphsPath, graphFile);
                
                if (s3Service.exists(graphKey)) {
                    if (!first) result.append(",");
                    
                    String presignedUrl = s3Service.getPresignedUrl(graphKey, Duration.ofHours(1));
                    String graphName = graphFile.replace(".png", "");
                    
                    result.append("\"").append(graphName).append("\": \"").append(presignedUrl).append("\"");
                    first = false;
                }
            }
            
            result.append("}}");
            return result.toString();
        });
    }
}

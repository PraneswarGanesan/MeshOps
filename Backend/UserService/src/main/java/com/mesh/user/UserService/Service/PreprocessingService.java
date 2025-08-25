package com.mesh.user.UserService.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PreprocessingService {

    private final LambdaClient lambdaClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${aws.lambda.preprocessing-function-name}")
    private String functionName;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    /**
     * Triggers Lambda with payload: { bucket, prefix, files[] }
     * If files is null/empty, Lambda will auto-discover under prefix.
     */
    public String trigger(String username, String project, String folder, List<String> files) {
        try {
            StringBuilder prefix = new StringBuilder();
            prefix.append(username).append("/").append(project).append("/");
            if (folder != null && !folder.isBlank()) {
                // sanitize leading slash
                String f = folder.startsWith("/") ? folder.substring(1) : folder;
                if (!f.endsWith("/")) f += "/";
                prefix.append(f);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("bucket", bucketName);
            payload.put("prefix", prefix.toString());
            if (files != null) payload.put("files", files); // may be empty => Lambda auto-discovers

            byte[] json = objectMapper.writeValueAsBytes(payload);

            InvokeRequest req = InvokeRequest.builder()
                    .functionName(functionName)
                    .payload(SdkBytes.fromByteArray(json))
                    .build();

            InvokeResponse resp = lambdaClient.invoke(req);
            int code = resp.statusCode();
            String body = resp.payload() != null ? resp.payload().asUtf8String() : "";

            if (code == 200) {
                return body; // Lambda already returns a JSON body string
            } else {
                throw new RuntimeException("Lambda invocation failed. HTTP " + code + " Body: " + body);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke preprocessing Lambda", e);
        }
    }
}

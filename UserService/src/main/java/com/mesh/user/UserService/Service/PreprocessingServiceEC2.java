package com.mesh.user.UserService.Service;

import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PreprocessingServiceEC2 {

    @Value("${ec2.host}")
    private String ec2Host;

    @Value("${ec2.user}")
    private String ec2User;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    // Absolute path to PEM on Windows
    private static final String PEM_PATH = "C:/SSHKeys/MeshopsPre.pem";

    public String trigger(String username, String projectName, String folder, List<String> files) {
        Session session = null;
        ChannelExec channel = null;

        try {
            JSch jsch = new JSch();

            // Load PEM key
            jsch.addIdentity("C:/SSHKeys/MeshopsPre.pem");


            // Enable JSch debug to see auth issues
            JSch.setLogger(new com.jcraft.jsch.Logger() {
                @Override
                public boolean isEnabled(int level) { return true; }
                @Override
                public void log(int level, String message) { System.out.println("[JSch] " + message); }
            });

            // Connect to EC2
            session = jsch.getSession(ec2User, ec2Host, 22);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(10000); // 10s timeout

            // Prepare file arguments
            String fileArgs = (files == null || files.isEmpty())
                    ? ""
                    : files.stream().collect(Collectors.joining(","));

            // Construct S3 prefix
            String s3Prefix = username + "/" + projectName + "/";
            if (folder != null && !folder.isEmpty()) {
                s3Prefix += folder + "/";
            }

            // Command to run preprocessing on EC2
            String command = String.format(
                    "python3 preproc.py %s %s %s",
                    bucketName, s3Prefix, fileArgs
            );

            // Execute command
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ByteArrayOutputStream errorStream = new ByteArrayOutputStream();
            channel.setOutputStream(outputStream);
            channel.setErrStream(errorStream);

            channel.connect();

            while (!channel.isClosed()) {
                Thread.sleep(200);
            }

            String stdout = outputStream.toString().trim();
            String stderr = errorStream.toString().trim();

            if (!stderr.isEmpty()) {
                return "{\"status\":\"error\", \"message\":\"" + stderr.replace("\"", "\\\"") + "\"}";
            }

            return stdout;

        } catch (JSchException e) {
            // Capture auth failures
            return "{\"status\":\"auth_fail\", \"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            if (channel != null && !channel.isClosed()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }
}

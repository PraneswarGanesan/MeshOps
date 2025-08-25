package com.mesh.user.UserService.Service;

import com.jcraft.jsch.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

@Service
public class PreprocessingServiceEC2 {

    @Value("${ec2.host}") private String ec2Host;
    @Value("${ec2.user}") private String ec2User;
    @Value("${aws.s3.bucket}") private String bucketName;

    // Absolute PEM path
    private static final String PEM_PATH = "C:/SSHKeys/MeshopsPre.pem";

    public String trigger(String username, String projectName, String folder, List<String> files) {
        Session session = null;
        ChannelExec channel = null;

        try {
            JSch jsch = new JSch();
            jsch.addIdentity(PEM_PATH);

            // debug logging
            JSch.setLogger(new com.jcraft.jsch.Logger() {
                public boolean isEnabled(int level) { return true; }
                public void log(int level, String message) { System.out.println("[JSch] " + message); }
            });

            session = jsch.getSession(ec2User, ec2Host, 22);
            session.setConfig("StrictHostKeyChecking", "no");
            session.connect(15000);

            // --- build S3 prefix ---
            StringBuilder prefix = new StringBuilder();
            prefix.append(username).append("/").append(projectName).append("/");

            String safeFolder = (folder == null) ? "" : folder.trim();
            if (!safeFolder.isEmpty()) {
                safeFolder = safeFolder.replace("\\", "/");
                int lastSlash = safeFolder.lastIndexOf('/');
                String lastSeg = lastSlash >= 0 ? safeFolder.substring(lastSlash + 1) : safeFolder;
                // if looks like a file (has extension), drop it
                if (lastSeg.contains(".")) {
                    safeFolder = (lastSlash >= 0) ? safeFolder.substring(0, lastSlash) : "";
                }
                if (!safeFolder.isEmpty()) {
                    if (!safeFolder.endsWith("/")) safeFolder += "/";
                    prefix.append(safeFolder);
                }
            }

            // --- build command ---
            StringBuilder cmd = new StringBuilder();
            cmd.append("python3 preproc.py ")
                    .append(escape(bucketName)).append(" ")
                    .append(escape(prefix.toString()));

            // Optional files
            if (files != null && !files.isEmpty()) {
                cmd.append(" --files");
                for (String f : files) {
                    if (f == null || f.trim().isEmpty()) continue;
                    String ff = f.trim().replace("\\", "/");
                    // Strip folder prefix if duplicate
                    if (!safeFolder.isEmpty() && ff.startsWith(safeFolder)) {
                        ff = ff.substring(safeFolder.length()).replaceFirst("^/", "");
                    }
                    cmd.append(" ").append(escape(ff));
                }
            }

            String command = cmd.toString();
            System.out.println("[EC2 CMD] " + command);

            // --- exec remote command ---
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setOutputStream(out);
            channel.setErrStream(err);

            channel.connect();

            while (!channel.isClosed()) Thread.sleep(150);

            String stdout = out.toString().trim();
            String stderr = err.toString().trim();

            if (!stderr.isEmpty() && stdout.isEmpty()) {
                return "{\"status\":\"error\",\"message\":\"" + stderr.replace("\"", "\\\"") + "\"}";
            }
            if (!stderr.isEmpty() && !stdout.isEmpty()) {
                return "{\"status\":\"warn\",\"stdout\":\"" + stdout.replace("\"", "\\\"")
                        + "\",\"stderr\":\"" + stderr.replace("\"", "\\\"") + "\"}";
            }
            return stdout.isEmpty()
                    ? "{\"status\":\"ok\",\"message\":\"completed\"}"
                    : stdout;

        } catch (JSchException e) {
            return "{\"status\":\"auth_fail\",\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        } finally {
            if (channel != null && !channel.isClosed()) channel.disconnect();
            if (session != null && session.isConnected()) session.disconnect();
        }
    }

    private static String escape(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}

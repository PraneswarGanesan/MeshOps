package com.mesh.user.UserService.Service;

import com.mesh.user.UserService.dto.LoginRequest;
import com.mesh.user.UserService.dto.LoginResponse;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.*;
import com.mesh.user.UserService.Repository.*;
import com.mesh.user.UserService.Model.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
@Service
public class ValidataionService {
    @Autowired
    private  UserRepository userRepository;

    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${spring.mail.username}")
    private String emailSender;
    public LoginResponse validateAndSendOtp(LoginRequest request) throws MessagingException {
        Optional<User> optionalUser = userRepository.findByUsername(request.getUsername());

        if (optionalUser.isEmpty()) {
            throw new RuntimeException("Invalid username or password");
        }

        User user = optionalUser.get();
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        if (!encoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid username or password");
        }

        int otp = new Random().nextInt(900000) + 100000;
        sendOtpEmail(user.getEmail(), otp);

        String token = Jwts.builder()
                .setSubject(user.getUsername())
                .claim("otp", otp)
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + 600_000)) // 10 mins
                .signWith(SignatureAlgorithm.HS256, jwtSecret)
                .compact();

        LoginResponse response = new LoginResponse();
        response.setMessage("OTP sent to your email");
        response.setOtp(otp);
        response.setToken(token);
        response.setUsername(user.getUsername());
        response.setUserType(user.getUserType());
        response.setPremiumType(user.getPremiumType());

        return response;
    }
    private void sendOtpEmail(String to, int otp) throws MessagingException {
        MimeMessage message = javaMailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true);

        helper.setTo(to);
        helper.setSubject("MeshOps.AI - Your OTP Code");
        String htmlContent = """
<!doctype html>
<html>
  <head>
    <meta name="viewport" content="width=device-width,initial-scale=1"/>
    <meta http-equiv="x-ua-compatible" content="ie=edge"/>
    <meta charSet="utf-8"/>
  </head>
  <body style="margin:0;padding:0;background:#0A0A29;font-family:'Segoe UI',Arial,sans-serif;color:#F8FAFC;">
    <div style="max-width:600px;margin:40px auto;padding:30px;background:rgba(10,10,41,0.92);border-radius:12px;box-shadow:0 0 25px rgba(212,175,55,0.25);text-align:center;">
      
      <h1 style="margin:0 0 10px;font-size:28px;font-weight:600;letter-spacing:2px;color:#D4AF37;">
        MeshOps<span style="color:#B69121;">.AI</span>
      </h1>

      <p style="margin:10px 0 22px;font-size:15px;color:#E5E7EB;">
        Your One-Time Password for verification is:
      </p>

      <!-- OTP chip: keep this block text-only; no extra spans/characters -->
      <div style="
        display:inline-block;
        background:linear-gradient(90deg,#D4AF37,#B69121);
        padding:16px 44px;
        border-radius:10px;
        box-shadow:0 0 20px rgba(212,175,55,0.45);
        line-height:1;               /* prevent extra line */
        mso-line-height-rule:exactly; /* Outlook */
      ">
        <span style="
          display:block;
          font-size:34px;
          font-weight:800;
          color:#0A0A29;
          letter-spacing:2px;
          font-variant-numeric:tabular-nums;
        ">
""" + otp + """
        </span>
      </div>

      <p style="margin:24px 0 0;font-size:13px;color:#d1d5db;">
        This OTP is valid for <strong>5 minutes</strong>. Do not share it with anyone.
      </p>

      <hr style="border:none;border-top:1px solid rgba(212,175,55,0.22);margin:28px 0"/>

      <p style="margin:0;font-size:11px;color:#9CA3AF;">
        &copy; 2025 MeshOps.AI — Secure AI Infrastructure
      </p>
    </div>
  </body>
</html>
""";


        helper.setText(htmlContent, true);

        javaMailSender.send(message);
    }



    private final Map<String, Integer> otpStore = new HashMap<>();

    public String sendOtpForPasswordReset(String email) throws MessagingException {
        Optional<User> optionalUser = userRepository.findByEmail(email);
        if (optionalUser.isEmpty()) {
            throw new RuntimeException("Email not registered.");
        }

        int otp = new Random().nextInt(900000) + 100000;
        otpStore.put(email, otp);
        sendOtpEmail(email, otp);
        return "OTP sent to " + email;
    }

    public boolean validateOtp(String email, int otp) {
        return otpStore.containsKey(email) && otpStore.get(email) == otp;
    }

    public void clearOtp(String email) {
        otpStore.remove(email);
    }
}


//
////Lambda python file
//import json
//import boto3
//
//def lambda_handler(event, context):
//username = event.get('username')
//project = event.get('projectName')
//folder = event.get('folder', '')
//files = event.get('files', [])
//
//    # Log received event
//print(f"Preprocessing request for user: {username}, project: {project}, folder: {folder}, files: {files}")
//
//s3 = boto3.client('s3')
//bucket = "your-s3-bucket-name"
//
//processed_files = []
//errors = []
//
//        for file_name in files:
//        try:
//key = f"{username}/{project}/{folder}/{file_name}" if folder else f"{username}/{project}/{file_name}"
//        # Download the file (you can do any preprocessing here)
//obj = s3.get_object(Bucket=bucket, Key=key)
//data = obj['Body'].read()
//
//            # Example: pretend to process data (you can add real logic here)
//print(f"Processing file: {key}, size: {len(data)} bytes")
//
//            # Optionally upload processed results back or somewhere else
//        # s3.put_object(Bucket=bucket, Key=processed_key, Body=processed_data)
//
//            processed_files.append(key)
//except Exception as e:
//        errors.append({"file": file_name, "error": str(e)})
//
//        return {
//        "statusCode": 200,
//        "processed_files": processed_files,
//        "errors": errors
//    }

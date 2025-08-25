package com.mesh.user.UserService.Controller;

import com.mesh.user.UserService.Service.AuthenticationService;
import com.mesh.user.UserService.Service.ValidataionService;
import com.mesh.user.UserService.dto.LoginRequest;
import com.mesh.user.UserService.dto.LoginResponse;
import com.mesh.user.UserService.dto.ResgisterRequest;
import jakarta.mail.MessagingException;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/users/auth")
@AllArgsConstructor
public class AuthController {
    private  final AuthenticationService authenticationService;
    private final ValidataionService validataionService;
    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody ResgisterRequest request) {
        try {
            return ResponseEntity.ok(authenticationService.register(request));
        }
        catch (IllegalArgumentException e){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid input"+e.getMessage());
        }
        catch (SecurityException e){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied"+e.getMessage());
        }
        catch (RuntimeException e){
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Registration Conflict"+e.getMessage());
        }
        catch (Exception e){
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Unexcpected error happend: "+e.getMessage());
        }
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        try {
            LoginResponse response = validataionService.validateAndSendOtp(request);
            return ResponseEntity.ok(response);
        } catch (MessagingException e) {
            return ResponseEntity.status(500).body("Failed to send OTP email: " + e.getMessage());
        }        catch (IllegalArgumentException e){
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid input"+e.getMessage());
        }
        catch (SecurityException e){
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied"+e.getMessage());
        }
        catch (RuntimeException e){
            return ResponseEntity.status(HttpStatus.CONFLICT).body("Registration Conflict"+e.getMessage());
        }
        catch (Exception e){
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Unexcpected error happend: "+e.getMessage());
        }
    }
}

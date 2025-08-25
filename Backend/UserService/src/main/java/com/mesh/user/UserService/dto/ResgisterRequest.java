package com.mesh.user.UserService.dto;


import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ResgisterRequest {

    @NotBlank(message = "username is required")
    private String username;
    @Email(message = "email is incorrect")

    private String email;
    @NotBlank(message = "please enter a password")
    @Size(min = 8 , message = "password should be minium 8 chcracters")
    private  String password;
    private String firstName;
    private String lastName;
    private LocalDateTime createdAT;
    private LocalDateTime updatedAT;
}

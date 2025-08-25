package com.mesh.user.UserService.Model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;


@Entity
@Data
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(nullable = false, unique = true)
    private String username;
    @Column(nullable = false,unique = true)
    private String email;
    @Column(nullable = false)
    private  String password;
    private String firstName;
    private String lastName;
    private String userType;
    private String premiumType;
    private LocalDateTime  createdAT;

}

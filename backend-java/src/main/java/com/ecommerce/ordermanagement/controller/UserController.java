package com.ecommerce.ordermanagement.controller;

import com.ecommerce.ordermanagement.model.User;
import com.ecommerce.ordermanagement.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Customer routes. PostgreSQL only. */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<User> listUsers() {
        return users.listUsers();
    }
}
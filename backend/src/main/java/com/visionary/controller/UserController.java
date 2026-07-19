package com.visionary.controller;

import com.visionary.dto.UserProfileDto;
import com.visionary.dto.UserRequest;
import com.visionary.entity.User;
import com.visionary.security.AuthContext;
import com.visionary.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public UserProfileDto getCurrentUser() {
        return userService.getCurrentUserProfile(requireUserId());
    }

    @PutMapping("/me")
    public UserProfileDto updateCurrentUser(@RequestBody UserProfileDto request) {
        return userService.updateCurrentUserProfile(requireUserId(), request);
    }

    @GetMapping
    public List<User> listUsers() {
        return userService.listUsers();
    }

    @GetMapping("/{id}")
    public User getUser(@PathVariable Long id) {
        return userService.getUser(id);
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody UserRequest request) {
        User created = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public User updateUser(@PathVariable Long id, @RequestBody UserRequest request) {
        return userService.updateUser(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    private static Long requireUserId() {
        return AuthContext.currentRegisteredUserId()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问用户资料"));
    }
}

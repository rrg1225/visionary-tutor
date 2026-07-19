package com.visionary.service;

import com.visionary.dto.UserProfileDto;
import com.visionary.dto.UserRequest;
import com.visionary.entity.User;
import com.visionary.exception.ResourceNotFoundException;
import com.visionary.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<User> listUsers() {
        return userRepository.findAll();
    }

    @Transactional(readOnly = true)
    public User getUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
    }

    @Transactional(readOnly = true)
    public UserProfileDto getCurrentUserProfile(Long userId) {
        return UserProfileDto.fromEntity(getUser(userId));
    }

    @Transactional
    public UserProfileDto updateCurrentUserProfile(Long userId, UserProfileDto request) {
        User user = getUser(userId);
        applyProfileDto(user, request);
        return UserProfileDto.fromEntity(userRepository.save(user));
    }

    @Transactional
    public User createUser(UserRequest request) {
        if (request.username() == null || request.username().isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (userRepository.existsByUsername(request.username())) {
            throw new IllegalArgumentException("username already exists");
        }
        if (request.email() != null && !request.email().isBlank() && userRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("email already exists");
        }

        User user = new User();
        applyRequest(user, request);
        if (user.getStatus() == null) {
            user.setStatus(User.UserStatus.ACTIVE);
        }
        return userRepository.save(user);
    }

    @Transactional
    public User updateUser(Long id, UserRequest request) {
        User user = getUser(id);
        applyRequest(user, request);
        return userRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("User not found: " + id);
        }
        userRepository.deleteById(id);
    }

    private void applyProfileDto(User user, UserProfileDto request) {
        if (request == null) {
            return;
        }
        if (request.email() != null && !request.email().isBlank()) {
            if (userRepository.existsByEmail(request.email())
                    && !request.email().equals(user.getEmail())) {
                throw new IllegalArgumentException("email already exists");
            }
            user.setEmail(request.email());
        }
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.gradeLevel() != null) {
            user.setGradeLevel(request.gradeLevel());
        }
        if (request.learningGoal() != null) {
            user.setLearningGoal(request.learningGoal());
        }
    }

    private void applyRequest(User user, UserRequest request) {
        if (request.username() != null && !request.username().isBlank()) {
            user.setUsername(request.username());
        }
        if (request.email() != null) {
            user.setEmail(request.email());
        }
        if (request.displayName() != null) {
            user.setDisplayName(request.displayName());
        }
        if (request.avatarUrl() != null) {
            user.setAvatarUrl(request.avatarUrl());
        }
        if (request.gradeLevel() != null) {
            user.setGradeLevel(request.gradeLevel());
        }
        if (request.learningGoal() != null) {
            user.setLearningGoal(request.learningGoal());
        }
        if (request.learnerProfileSnapshot() != null) {
            user.setLearnerProfileSnapshot(request.learnerProfileSnapshot());
        }
        if (request.emotionProfileSnapshot() != null) {
            user.setEmotionProfileSnapshot(request.emotionProfileSnapshot());
        }
        if (request.status() != null) {
            user.setStatus(request.status());
        }
    }
}

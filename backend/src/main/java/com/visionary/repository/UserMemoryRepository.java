package com.visionary.repository;

import com.visionary.entity.UserMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    List<UserMemory> findByUserIdAndIsActiveTrueOrderByPriorityDescGmtModifiedDesc(Long userId);

    List<UserMemory> findByUserIdAndReviewStatusOrderByGmtModifiedDesc(Long userId, String reviewStatus);

    List<UserMemory> findByUserIdOrderByGmtModifiedDesc(Long userId);

    Optional<UserMemory> findByUserIdAndMemoryTypeAndMemoryKey(Long userId, String memoryType, String memoryKey);
}

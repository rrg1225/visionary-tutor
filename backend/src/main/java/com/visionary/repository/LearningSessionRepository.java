package com.visionary.repository;

import com.visionary.entity.LearningSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * LearningSession Repository - 学习会话数据访问层
 * 
 * 增强：支持游客ID和正式用户ID的灵活查询，以及数据迁移操作
 */
@Repository
public interface LearningSessionRepository extends JpaRepository<LearningSession, Long> {

    /**
     * 根据用户ID查询所有学习会话（支持游客ID或正式用户ID）
     */
    List<LearningSession> findByUserIdOrderByGmtCreatedDesc(Long userId);

    List<LearningSession> findByUserIdOrderByGmtModifiedDesc(Long userId);

    List<LearningSession> findByUserIdAndStatus(Long userId, LearningSession.SessionStatus status);

    /**
     * 批量更新会话的用户ID（用于游客数据迁移）
     * 
     * @param oldUserId 原游客ID
     * @param newUserId 新正式用户ID
     * @return 更新的记录数
     */
    @Modifying
    @Query("UPDATE LearningSession ls SET ls.userId = :newUserId WHERE ls.userId = :oldUserId")
    int migrateUserId(@Param("oldUserId") Long oldUserId, @Param("newUserId") Long newUserId);

    /**
     * 查询指定用户ID的会话数量
     */
    long countByUserId(Long userId);

    boolean existsByIdAndUserId(Long id, Long userId);

    List<LearningSession> findByGmtModifiedAfterOrderByGmtModifiedDesc(LocalDateTime since);
}

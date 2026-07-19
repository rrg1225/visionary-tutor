package com.visionary.repository;

import com.visionary.entity.GuestSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * GuestSession Repository - 游客会话数据访问层
 */
@Repository
public interface GuestSessionRepository extends JpaRepository<GuestSession, String> {

    /**
     * 根据游客ID查询有效（未过期）的会话
     */
    @Query("SELECT g FROM GuestSession g WHERE g.guestId = :guestId AND g.expiresAt > :now")
    Optional<GuestSession> findValidByGuestId(@Param("guestId") String guestId, @Param("now") LocalDateTime now);

    /**
     * 查询指定正式用户转换前的游客记录
     */
    Optional<GuestSession> findByConvertedUserId(Long convertedUserId);

    /**
     * 查询所有已过期的游客会话（用于定时清理）
     */
    List<GuestSession> findByExpiresAtBefore(LocalDateTime now);

    /**
     * 查询所有未转换但已过期的会话
     */
    @Query("SELECT g FROM GuestSession g WHERE g.expiresAt < :now AND g.convertedUserId IS NULL")
    List<GuestSession> findExpiredAndNotConverted(@Param("now") LocalDateTime now);

    /**
     * 批量删除过期会话
     */
    @Modifying
    @Query("DELETE FROM GuestSession g WHERE g.expiresAt < :now AND g.convertedUserId IS NULL")
    int deleteExpiredSessions(@Param("now") LocalDateTime now);
}

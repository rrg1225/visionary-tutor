package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GuestSession;
import com.visionary.entity.LearningSession;
import com.visionary.entity.SessionChatMessage;
import com.visionary.exception.GuestChatQuotaExceededException;
import com.visionary.repository.GuestSessionRepository;
import com.visionary.repository.LearningSessionRepository;
import com.visionary.repository.SessionChatMessageRepository;
import com.visionary.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 游客会话：MySQL 仅存档（迁移/审计），热路径会话与对话配额走 Redis（1 小时 TTL）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuestSessionService {

    private static final String KEY_SESSION_PREFIX = "vt:guest:";
    private static final String KEY_SESSION_SUFFIX = ":session";
    private static final String KEY_TURNS_SUFFIX = ":chat_turns";

    /** 与前端 useRequireAuth CONVERSATION_TURN_THRESHOLD 一致：已用满 N 次后下一条拦截 */
    public static final int DEFAULT_MAX_GUEST_CHAT_TURNS = 5;

    private final GuestSessionRepository guestSessionRepository;
    private final LearningSessionRepository learningSessionRepository;
    private final SessionChatMessageRepository sessionChatMessageRepository;
    private final ObjectMapper objectMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${visionary.guest.redis-ttl-hours:1}")
    private int guestRedisTtlHours;

    @Value("${visionary.guest.max-chat-turns:5}")
    private int maxGuestChatTurns;

    /** MySQL 归档会话有效期（迁移用），与 Redis 热缓存分离 */
    private static final int GUEST_SESSION_DAYS = 7;

    private Duration guestRedisTtl() {
        return Duration.ofHours(Math.max(1, guestRedisTtlHours));
    }

    private String sessionKey(String guestId) {
        return KEY_SESSION_PREFIX + guestId + KEY_SESSION_SUFFIX;
    }

    private String turnsKey(String guestId) {
        return KEY_SESSION_PREFIX + guestId + KEY_TURNS_SUFFIX;
    }

    private void refreshRedisTtl(String guestId) {
        Duration ttl = guestRedisTtl();
        stringRedisTemplate.expire(sessionKey(guestId), ttl);
        stringRedisTemplate.expire(turnsKey(guestId), ttl);
    }

    /**
     * 创建游客：MySQL 写一次 + Redis 初始化配额。
     */
    @Transactional
    public GuestSessionResult createGuestSession(String deviceFingerprint,
                                                  String contextJson,
                                                  String ipAddress) {
        String guestId = generateGuestId();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusDays(GUEST_SESSION_DAYS);

        GuestSession session = new GuestSession();
        session.setGuestId(guestId);
        session.setContextJson(contextJson);
        session.setExpiresAt(expiresAt);
        session.setDeviceFingerprint(deviceFingerprint);
        session.setIpAddress(ipAddress);
        guestSessionRepository.save(session);

        initRedisGuestSession(guestId, deviceFingerprint, contextJson, ipAddress);

        String token = jwtUtil.generateGuestToken(guestId);
        GuestChatQuota quota = getChatQuota(guestId);

        log.info("Created guest session: guestId={}, redisTtlHours={}", guestId, guestRedisTtlHours);

        return new GuestSessionResult(guestId, token, expiresAt, quota);
    }

    private void initRedisGuestSession(String guestId,
                                       String deviceFingerprint,
                                       String contextJson,
                                       String ipAddress) {
        Map<String, String> meta = new HashMap<>();
        meta.put("guestId", guestId);
        meta.put("deviceFingerprint", deviceFingerprint != null ? deviceFingerprint : "");
        meta.put("contextJson", contextJson != null ? contextJson : "");
        meta.put("ipAddress", ipAddress != null ? ipAddress : "");
        meta.put("createdAt", String.valueOf(System.currentTimeMillis()));

        stringRedisTemplate.opsForHash().putAll(sessionKey(guestId), meta);
        stringRedisTemplate.opsForValue().set(turnsKey(guestId), "0");
        refreshRedisTtl(guestId);
    }

    /**
     * 校验游客：优先 Redis，缺失时从 MySQL 回灌（仅一次读库）。
     */
    @Transactional(readOnly = true)
    public Optional<GuestSession> validateGuestSession(String guestId) {
        if (guestId == null || !guestId.startsWith("gst_")) {
            return Optional.empty();
        }

        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(sessionKey(guestId)))) {
            return guestSessionRepository.findValidByGuestId(guestId, LocalDateTime.now());
        }

        Optional<GuestSession> sessionOpt = guestSessionRepository.findValidByGuestId(guestId, LocalDateTime.now());
        sessionOpt.ifPresent(session -> rehydrateRedisFromEntity(session, readUsedTurns(guestId)));
        return sessionOpt;
    }

    private void rehydrateRedisFromEntity(GuestSession session, int usedTurns) {
        initRedisGuestSession(
                session.getGuestId(),
                session.getDeviceFingerprint(),
                session.getContextJson(),
                session.getIpAddress()
        );
        stringRedisTemplate.opsForValue().set(turnsKey(session.getGuestId()), String.valueOf(usedTurns));
        refreshRedisTtl(session.getGuestId());
        log.debug("Rehydrated guest Redis cache: guestId={}, usedTurns={}", session.getGuestId(), usedTurns);
    }

    private int readUsedTurns(String guestId) {
        String raw = stringRedisTemplate.opsForValue().get(turnsKey(guestId));
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public GuestChatQuota getChatQuota(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return GuestChatQuota.empty();
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(sessionKey(guestId)))) {
            validateGuestSession(guestId);
        }
        int used = readUsedTurns(guestId);
        long ttlSeconds = Optional.ofNullable(
                stringRedisTemplate.getExpire(sessionKey(guestId), TimeUnit.SECONDS)
        ).orElse(0L);
        if (ttlSeconds < 0) {
            ttlSeconds = guestRedisTtl().toSeconds();
        }
        return GuestChatQuota.of(used, maxGuestChatTurns, ttlSeconds);
    }

    /**
     * 消耗一次游客对话配额；不足时返回 false。
     */
    public boolean tryConsumeChatTurn(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return false;
        }
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(sessionKey(guestId)))) {
            if (validateGuestSession(guestId).isEmpty()) {
                return false;
            }
        }

        int used = readUsedTurns(guestId);
        if (used >= maxGuestChatTurns) {
            log.info("Guest chat quota exceeded: guestId={}, used={}", guestId, used);
            return false;
        }

        stringRedisTemplate.opsForValue().increment(turnsKey(guestId));
        refreshRedisTtl(guestId);
        log.debug("Guest chat turn consumed: guestId={}, usedAfter={}", guestId, used + 1);
        return true;
    }

    public void assertCanConsumeChatTurn(String guestId) {
        if (!tryConsumeChatTurn(guestId)) {
            throw new GuestChatQuotaExceededException(getChatQuota(guestId));
        }
    }

    public void clearGuestRedis(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(sessionKey(guestId));
        stringRedisTemplate.delete(turnsKey(guestId));
    }

    @Transactional
    public boolean extendSession(String guestId) {
        Optional<GuestSession> sessionOpt = validateGuestSession(guestId);
        if (sessionOpt.isEmpty()) {
            return false;
        }

        GuestSession session = sessionOpt.get();
        session.setExpiresAt(LocalDateTime.now().plusDays(GUEST_SESSION_DAYS));
        guestSessionRepository.save(session);
        refreshRedisTtl(guestId);

        log.info("Extended guest session: guestId={}", guestId);
        return true;
    }

    @Transactional
    public MigrationResult migrateToUser(String guestId, Long userId) {
        Optional<GuestSession> sessionOpt = validateGuestSession(guestId);
        if (sessionOpt.isEmpty()) {
            log.warn("Migration failed: guest session not found or expired, guestId={}", guestId);
            return MigrationResult.failed("Guest session not found or expired");
        }

        GuestSession guestSession = sessionOpt.get();

        if (guestSession.isConverted()) {
            log.info("Guest session already converted: guestId={}, userId={}",
                    guestId, guestSession.getConvertedUserId());
            clearGuestRedis(guestId);
            return MigrationResult.alreadyConverted(guestSession.getConvertedUserId());
        }

        guestSession.setMigrationStatus("MIGRATING");
        guestSession.setMigrationError(null);
        guestSessionRepository.save(guestSession);

        int migratedSessions = migrateSnapshot(guestSession, userId);
        if (migratedSessions < 0) {
            return MigrationResult.failed(guestSession.getMigrationError());
        }

        guestSession.setConvertedUserId(userId);
        guestSession.setMigrationStatus("COMPLETED");
        guestSession.setMigratedAt(LocalDateTime.now());
        guestSessionRepository.save(guestSession);
        clearGuestRedis(guestId);

        log.info("Successfully migrated guest {} to user {}, migrated {} sessions",
                guestId, userId, migratedSessions);

        return MigrationResult.success(guestId, userId, migratedSessions, 0);
    }

    @Transactional
    public int cleanupExpiredSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<GuestSession> expiredSessions = guestSessionRepository.findExpiredAndNotConverted(now);

        if (expiredSessions.isEmpty()) {
            return 0;
        }

        int deleted = guestSessionRepository.deleteExpiredSessions(now);
        log.info("Cleaned up {} expired guest sessions", deleted);
        return deleted;
    }

    @Transactional
    public boolean updateContext(String guestId, String contextJson) {
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(sessionKey(guestId)))) {
            if (validateGuestSession(guestId).isEmpty()) {
                return false;
            }
        }

        stringRedisTemplate.opsForHash().put(sessionKey(guestId), "contextJson",
                contextJson != null ? contextJson : "");
        guestSessionRepository.findById(guestId).ifPresent(session -> {
            session.setContextJson(contextJson);
            session.setMigrationStatus("PENDING");
            session.setMigrationError(null);
            guestSessionRepository.save(session);
        });
        refreshRedisTtl(guestId);
        return true;
    }

    private int migrateSnapshot(GuestSession guestSession, Long userId) {
        String contextJson = guestSession.getContextJson();
        if (contextJson == null || contextJson.isBlank()) {
            return 0;
        }
        try {
            JsonNode snapshot = objectMapper.readTree(contextJson);
            JsonNode messages = snapshot.path("messages");
            boolean hasMessages = messages.isArray() && !messages.isEmpty();
            boolean hasStoredState = snapshot.path("storage").isObject()
                    && !snapshot.path("storage").isEmpty();
            if (!hasMessages && !hasStoredState) {
                return 0;
            }

            LearningSession learningSession = new LearningSession();
            learningSession.setUserId(userId);
            learningSession.setTopic(snapshot.path("topic").asText("游客体验迁移记录"));
            learningSession.setStatus(LearningSession.SessionStatus.PAUSED);
            learningSession.setCurrentPhase(LearningSession.LearningPhase.STUDENT_PROFILE);
            learningSession.setConversationSummary(objectMapper.writeValueAsString(snapshot.path("storage")));
            learningSession = learningSessionRepository.save(learningSession);
            guestSession.setMigratedSessionId(learningSession.getId());

            int sequence = 1;
            if (hasMessages) {
                for (JsonNode message : messages) {
                    if (sequence > 200) break;
                    String role = message.path("role").asText("");
                    String content = message.path("content").asText("");
                    if (!("user".equals(role) || "assistant".equals(role)) || content.isBlank()) continue;
                    SessionChatMessage row = new SessionChatMessage();
                    row.setLearningSessionId(learningSession.getId());
                    row.setUserId(userId);
                    row.setRole(role);
                    row.setContent(content.substring(0, Math.min(content.length(), 20_000)));
                    row.setSeq(sequence++);
                    row.setContextType("GENERAL");
                    row.setContextKey("");
                    row.setMetadataJson("{\"source\":\"guest-migration\"}");
                    sessionChatMessageRepository.save(row);
                }
            }
            return 1;
        } catch (Exception e) {
            guestSession.setMigrationStatus("FAILED");
            String message = e.getMessage() == null ? "Guest snapshot migration failed" : e.getMessage();
            guestSession.setMigrationError(message.substring(0, Math.min(message.length(), 512)));
            guestSessionRepository.save(guestSession);
            return -1;
        }
    }

    private String generateGuestId() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        return "gst_" + uuid;
    }

    public record GuestSessionResult(
            String guestId,
            String token,
            LocalDateTime expiresAt,
            GuestChatQuota chatQuota
    ) {}

    public record GuestChatQuota(
            int usedTurns,
            int maxTurns,
            int remainingTurns,
            long sessionTtlSeconds
    ) {
        public static GuestChatQuota of(int usedTurns, int maxTurns, long sessionTtlSeconds) {
            int remaining = Math.max(0, maxTurns - usedTurns);
            return new GuestChatQuota(usedTurns, maxTurns, remaining, sessionTtlSeconds);
        }

        public static GuestChatQuota empty() {
            return of(0, DEFAULT_MAX_GUEST_CHAT_TURNS, 0);
        }

        public boolean quotaExceeded() {
            return usedTurns >= maxTurns;
        }
    }

    public record MigrationResult(
            boolean success,
            String guestId,
            Long userId,
            int migratedSessionsCount,
            int migratedReportsCount,
            String message,
            boolean alreadyConverted
    ) {
        public static MigrationResult success(String guestId, Long userId,
                                               int sessionsCount, int reportsCount) {
            return new MigrationResult(true, guestId, userId, sessionsCount,
                    reportsCount, "Migration successful", false);
        }

        public static MigrationResult failed(String message) {
            return new MigrationResult(false, null, null, 0, 0, message, false);
        }

        public static MigrationResult alreadyConverted(Long existingUserId) {
            return new MigrationResult(true, null, existingUserId, 0, 0,
                    "Already converted", true);
        }
    }
}

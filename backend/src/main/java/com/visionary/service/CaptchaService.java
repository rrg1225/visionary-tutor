package com.visionary.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CaptchaService {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int CAPTCHA_LENGTH = 4;
    private static final int MAX_ACTIVE_CHALLENGES = 10_000;
    private static final int MAX_ISSUES_PER_MINUTE = 60;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /**
     * Five-by-seven bitmap glyphs rendered as SVG rectangles. No text node contains the
     * answer, and generation does not depend on AWT, ImageIO, system fonts or a display.
     */
    private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
            glyph('2', "11111/00001/00001/11111/10000/10000/11111"),
            glyph('3', "11111/00001/00001/01111/00001/00001/11111"),
            glyph('4', "10001/10001/10001/11111/00001/00001/00001"),
            glyph('5', "11111/10000/10000/11111/00001/00001/11111"),
            glyph('6', "11111/10000/10000/11111/10001/10001/11111"),
            glyph('7', "11111/00001/00010/00100/01000/01000/01000"),
            glyph('8', "11111/10001/10001/11111/10001/10001/11111"),
            glyph('9', "11111/10001/10001/11111/00001/00001/11111"),
            glyph('A', "01110/10001/10001/11111/10001/10001/10001"),
            glyph('B', "11110/10001/10001/11110/10001/10001/11110"),
            glyph('C', "01111/10000/10000/10000/10000/10000/01111"),
            glyph('D', "11110/10001/10001/10001/10001/10001/11110"),
            glyph('E', "11111/10000/10000/11110/10000/10000/11111"),
            glyph('F', "11111/10000/10000/11110/10000/10000/10000"),
            glyph('G', "01111/10000/10000/10111/10001/10001/01111"),
            glyph('H', "10001/10001/10001/11111/10001/10001/10001"),
            glyph('J', "00111/00010/00010/00010/00010/10010/01100"),
            glyph('K', "10001/10010/10100/11000/10100/10010/10001"),
            glyph('L', "10000/10000/10000/10000/10000/10000/11111"),
            glyph('M', "10001/11011/10101/10101/10001/10001/10001"),
            glyph('N', "10001/11001/10101/10011/10001/10001/10001"),
            glyph('P', "11110/10001/10001/11110/10000/10000/10000"),
            glyph('Q', "01110/10001/10001/10001/10101/10010/01101"),
            glyph('R', "11110/10001/10001/11110/10100/10010/10001"),
            glyph('S', "01111/10000/10000/01110/00001/00001/11110"),
            glyph('T', "11111/00100/00100/00100/00100/00100/00100"),
            glyph('U', "10001/10001/10001/10001/10001/10001/01110"),
            glyph('V', "10001/10001/10001/10001/10001/01010/00100"),
            glyph('W', "10001/10001/10001/10101/10101/11011/10001"),
            glyph('X', "10001/10001/01010/00100/01010/10001/10001"),
            glyph('Y', "10001/10001/01010/00100/00100/00100/00100"),
            glyph('Z', "11111/00001/00010/00100/01000/10000/11111")
    );

    private final SecureRandom random = new SecureRandom();
    private final Map<String, CaptchaEntry> challenges = new ConcurrentHashMap<>();
    private final Map<String, RequestWindow> issuanceWindows = new ConcurrentHashMap<>();

    public CaptchaChallenge createChallenge() {
        return createChallenge("local");
    }

    public CaptchaChallenge createChallenge(String clientKey) {
        Instant now = Instant.now();
        removeExpired(now);
        enforceRateLimit(clientKey, now);
        if (challenges.size() >= MAX_ACTIVE_CHALLENGES) {
            throw new IllegalStateException("验证码服务繁忙，请稍后重试");
        }

        String id = UUID.randomUUID().toString();
        String answer = randomAnswer();
        challenges.put(id, new CaptchaEntry(answer, now.plus(TTL)));
        return new CaptchaChallenge(id, toSvgDataUrl(answer), TTL.toSeconds());
    }

    public void verify(String id, String answer) {
        if (id == null || answer == null) {
            throw new IllegalArgumentException("请完成图形验证码");
        }
        CaptchaEntry entry = challenges.remove(id);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("图形验证码已失效，请刷新后重试");
        }
        if (!entry.answer().equals(answer.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("图形验证码不正确，请重新输入");
        }
    }

    private String randomAnswer() {
        StringBuilder answer = new StringBuilder(CAPTCHA_LENGTH);
        for (int i = 0; i < CAPTCHA_LENGTH; i++) {
            answer.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return answer.toString();
    }

    private String toSvgDataUrl(String answer) {
        StringBuilder svg = new StringBuilder(6_000);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"150\" height=\"48\" viewBox=\"0 0 150 48\">")
                .append("<defs><linearGradient id=\"bg\"><stop stop-color=\"#eef2ff\"/><stop offset=\"1\" stop-color=\"#ecfeff\"/></linearGradient></defs>")
                .append("<rect width=\"150\" height=\"48\" rx=\"8\" fill=\"url(#bg)\"/>");

        for (int i = 0; i < answer.length(); i++) {
            appendGlyph(svg, answer.charAt(i), 10 + i * 27, 8, i);
        }
        for (int i = 0; i < 2; i++) {
            String color = i % 2 == 0 ? "#6366f1" : "#14b8a6";
            svg.append("<path d=\"M").append(random.nextInt(150)).append(' ').append(random.nextInt(48))
                    .append(" L").append(random.nextInt(150)).append(' ').append(random.nextInt(48))
                    .append("\" stroke=\"").append(color).append("\" stroke-opacity=\".28\" stroke-width=\"1.2\"/>");
        }
        for (int i = 0; i < 12; i++) {
            svg.append("<circle cx=\"").append(random.nextInt(150)).append("\" cy=\"")
                    .append(random.nextInt(48)).append("\" r=\"").append(1 + random.nextInt(2))
                    .append("\" fill=\"#0f766e\" fill-opacity=\".18\"/>");
        }
        svg.append("</svg>");
        return "data:image/svg+xml;base64," + Base64.getEncoder()
                .encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendGlyph(StringBuilder svg, char character, int originX, int originY, int index) {
        String[] rows = GLYPHS.get(character);
        int angle = random.nextInt(7) - 3;
        int yJitter = random.nextInt(3) - 1;
        String color = index % 2 == 0 ? "#1e293b" : "#4338ca";
        svg.append("<g transform=\"translate(").append(originX).append(' ').append(originY + yJitter)
                .append(") rotate(").append(angle).append(" 7 14)\" fill=\"").append(color).append("\">");
        for (int row = 0; row < rows.length; row++) {
            for (int column = 0; column < rows[row].length(); column++) {
                if (rows[row].charAt(column) == '1') {
                    svg.append("<rect x=\"").append(column * 2.6).append("\" y=\"").append(row * 4)
                            .append("\" width=\"2.9\" height=\"4.3\" rx=\".45\"/>");
                }
            }
        }
        svg.append("</g>");
    }

    private void enforceRateLimit(String clientKey, Instant now) {
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        RequestWindow window = issuanceWindows.computeIfAbsent(key, ignored -> new RequestWindow());
        synchronized (window) {
            Instant cutoff = now.minus(RATE_WINDOW);
            while (!window.issuedAt.isEmpty() && window.issuedAt.peekFirst().isBefore(cutoff)) {
                window.issuedAt.removeFirst();
            }
            if (window.issuedAt.size() >= MAX_ISSUES_PER_MINUTE) {
                throw new IllegalArgumentException("验证码请求过于频繁，请稍后重试");
            }
            window.issuedAt.addLast(now);
        }
    }

    private void removeExpired(Instant now) {
        challenges.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        Instant cutoff = now.minus(RATE_WINDOW);
        issuanceWindows.entrySet().removeIf(entry -> {
            RequestWindow window = entry.getValue();
            synchronized (window) {
                return window.issuedAt.isEmpty() || window.issuedAt.peekLast().isBefore(cutoff);
            }
        });
    }

    private static Map.Entry<Character, String[]> glyph(char character, String rows) {
        return Map.entry(character, rows.split("/"));
    }

    private static final class RequestWindow {
        private final Deque<Instant> issuedAt = new ArrayDeque<>();
    }

    private record CaptchaEntry(String answer, Instant expiresAt) {
    }

    public record CaptchaChallenge(String captchaId, String imageDataUrl, long expiresInSeconds) {
    }
}

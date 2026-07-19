package com.visionary.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.entity.GenerationEvent;
import com.visionary.repository.AgentRunStepRepository;
import com.visionary.repository.GeneratedArtifactRepository;
import com.visionary.repository.GenerationEventRepository;
import com.visionary.resourcegeneration.domain.GenerationState;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real competition smoke path. Nothing in this class mocks Spring MVC, JPA,
 * Redis or Chroma: all network and persistence boundaries run in containers.
 * Only the paid model provider is replaced by an HTTP-compatible local server,
 * so the API contract is deterministic and does not consume contest secrets.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CompetitionLearningLoopIT {

    private static final ObjectMapper MODEL_JSON = new ObjectMapper();
    private static final AtomicInteger PROFILE_MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger SPECIALIST_MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger FACTUALITY_MODEL_CALLS = new AtomicInteger();
    private static final AtomicInteger CRITIC_MODEL_CALLS = new AtomicInteger();
    private static final Pattern CITATION_ID = Pattern.compile("cite-[a-zA-Z0-9_.:-]+");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("visionary_tutor")
            .withUsername("visionary")
            .withPassword("visionary");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Container
    static final GenericContainer<?> CHROMA = new GenericContainer<>(DockerImageName.parse("chromadb/chroma:0.5.23"))
            .withEnv("ANONYMIZED_TELEMETRY", "FALSE")
            .withExposedPorts(8000);

    static final MockWebServer MODEL_SERVER = startModelServer();

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    GeneratedArtifactRepository artifactRepository;

    @Autowired
    AgentRunStepRepository stepRepository;

    @Autowired
    GenerationEventRepository generationEventRepository;

    static String token;
    static long userId;
    static long sessionId;

    static MockWebServer startModelServer() {
        try {
            MockWebServer server = new MockWebServer();
            server.setDispatcher(new DeterministicModelDispatcher());
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void stopModelServer() throws IOException {
        MODEL_SERVER.shutdown();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("vector.db.host", CHROMA::getHost);
        registry.add("vector.db.port", () -> CHROMA.getMappedPort(8000));
        registry.add("vector.db.knowledge-base-path", () -> "");
        registry.add("vector.db.embedding-model", () -> "all-MiniLM-L6-v2");
        registry.add("ai.api.deep-seek-key", () -> "integration-key");
        registry.add("ai.api.deep-seek-base-url", () -> MODEL_SERVER.url("/").toString().replaceAll("/$", ""));
        registry.add("jwt.secret", () -> "integration-only-secret-key-at-least-32-bytes-long");
        registry.add("visionary.demo-mode.enabled", () -> "false");
        registry.add("visionary.showcase.seed-on-startup", () -> "false");
        registry.add("visionary.agent.governance.max-revision-rounds", () -> "0");
        registry.add("agent.mode", () -> "workflow");
    }

    @Test
    @Order(1)
    void seedAndAuthenticateAgainstRealMysqlAndRedis() throws Exception {
        HttpHeaders seedHeaders = new HttpHeaders();
        seedHeaders.set("X-Demo-Seed-Token", "integration-seed");
        ResponseEntity<String> seeded = rest.exchange(url("/api/demo/seed"), HttpMethod.POST,
                new HttpEntity<>(seedHeaders), String.class);
        assertThat(seeded.getStatusCode().is2xxSuccessful()).isTrue();
        sessionId = objectMapper.readTree(seeded.getBody()).path("learningSessionId").asLong();

        Map<String, Object> login = Map.of(
                "username", "demo_student",
                "password", "Demo@2026",
                "guestId", ""
        );
        ResponseEntity<String> response = rest.postForEntity(url("/api/auth/login"), login, String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode json = objectMapper.readTree(response.getBody());
        token = json.path("token").asText();
        userId = json.path("user").path("id").asLong();
        assertThat(token).isNotBlank();
        assertThat(userId).isPositive();
        assertThat(sessionId).isPositive();
    }

    @Test
    @Order(2)
    void profileExtractionCrossesTheRealModelHttpContract() throws Exception {
        ResponseEntity<String> response = rest.exchange(url("/api/profile/extract"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "userId", userId,
                        "conversationText", "I understand CNN basics but confuse padding and stride.",
                        "assessmentSummary", "baseline 42 percent",
                        "previousProfileSnapshot", "{}",
                        "emotionSnapshot", "focused",
                        "phase", "FULL"
                ), authHeaders()), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(objectMapper.readTree(response.getBody()).path("llmUsed").asBoolean()).isTrue();
        var modelRequest = MODEL_SERVER.takeRequest(5, TimeUnit.SECONDS);
        assertThat(modelRequest)
                .as("profile extraction must call the configured model HTTP endpoint")
                .isNotNull();
        assertThat(modelRequest.getPath()).isEqualTo("/chat/completions");
        assertThat(PROFILE_MODEL_CALLS.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @Order(3)
    void liveMultiAgentGenerationCrossesModelRagCriticAndPersistenceBoundaries() throws Exception {
        int specialistCallsBefore = SPECIALIST_MODEL_CALLS.get();
        int factualityCallsBefore = FACTUALITY_MODEL_CALLS.get();
        int criticCallsBefore = CRITIC_MODEL_CALLS.get();

        ResponseEntity<String> generated = rest.exchange(url("/api/resources/generate"), HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "learningSessionId", sessionId,
                        "topic", "CNN padding stride convolution output size",
                        "learnerProfileSnapshot", "visual learner with basic PyTorch experience",
                        "weakPointsSnapshot", "padding stride output-size formula",
                        "emotionSnapshot", "focused",
                        "resourceTypes", List.of(
                                "HANDOUT", "QUIZ", "MINDMAP", "LEARNING_PATH", "CODE_PRACTICE")
                ), authHeaders()), String.class);

        assertThat(generated.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode response = objectMapper.readTree(generated.getBody());
        String runId = response.path("runId").asText();
        assertThat(runId).isNotBlank().isNotEqualTo("demo-cnn-run");
        assertThat(response.path("artifacts").size()).isEqualTo(5);
        assertThat(response.path("steps").toString())
                .contains("PlannerAgent", "CriticAgent", "ReviewAgent", "DocAgent", "QuizAgent");

        assertThat(SPECIALIST_MODEL_CALLS.get() - specialistCallsBefore).isGreaterThanOrEqualTo(5);
        assertThat(FACTUALITY_MODEL_CALLS.get() - factualityCallsBefore).isGreaterThanOrEqualTo(5);
        assertThat(CRITIC_MODEL_CALLS.get() - criticCallsBefore).isGreaterThanOrEqualTo(5);

        var persistedArtifacts = artifactRepository.findByRunIdOrderByIdAsc(runId);
        assertThat(persistedArtifacts).hasSize(5);
        assertThat(persistedArtifacts)
                .allSatisfy(artifact -> {
                    assertThat(artifact.getContentJson()).contains("\"origin\":\"LIVE\"");
                    assertThat(artifact.getCitationsJson()).isNotBlank().isNotEqualTo("[]");
                    assertThat(artifact.getReviewNotes()).contains("CriticReport");
                });

        assertThat(stepRepository.findByRunIdOrderByStepOrderAsc(runId))
                .extracting(step -> step.getAgentName())
                .contains("PlannerAgent", "CriticAgent", "ReviewAgent", "DocAgent", "QuizAgent");

        List<GenerationEvent> events = generationEventRepository.findByTraceIdOrderByOccurredAtAsc(runId);
        assertThat(events).extracting(GenerationEvent::getToState).containsSubsequence(
                GenerationState.CREATED,
                GenerationState.PLANNING,
                GenerationState.RETRIEVING,
                GenerationState.GENERATING,
                GenerationState.CRITIQUING,
                GenerationState.PERSISTING,
                GenerationState.SUCCEEDED
        );
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getToState()).isEqualTo(GenerationState.RETRIEVING);
            assertThat(event.getAgent()).isEqualTo("RagRetrievalService");
        });
    }

    @Test
    @Order(4)
    void pretestUsagePosttestAndAssessmentCloseTheLearningLoop() throws Exception {
        authorizedPost("/api/resources/learning/pre-test", Map.of(
                "userId", userId, "learningSessionId", sessionId,
                "concept", "CNN padding/stride", "scorePercent", 42
        ));
        authorizedPost("/api/resources/usage/record", Map.of(
                "userId", userId, "learningSessionId", sessionId,
                "resourceId", 1, "actionType", "complete",
                "durationSeconds", 480, "feedback", "formula understood"
        ));
        authorizedPost("/api/resources/learning/post-test", Map.of(
                "userId", userId, "learningSessionId", sessionId,
                "concept", "CNN padding/stride", "scorePercent", 82
        ));

        ResponseEntity<String> report = authorizedGet(
                "/api/resources/learning/effect-experiment?userId=" + userId + "&learningSessionId=" + sessionId);
        JsonNode json = objectMapper.readTree(report.getBody());
        assertThat(json.path("overall").path("postTestAverage").asDouble())
                .isGreaterThan(json.path("overall").path("preTestAverage").asDouble());
        assertThat(json.path("evidenceLog").size()).isGreaterThan(2);
    }

    private ResponseEntity<String> authorizedGet(String path) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(authHeaders()), String.class);
    }

    private void authorizedPost(String path, Object body) {
        ResponseEntity<String> response = rest.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, authHeaders()), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static MockResponse modelResponse(String content) {
        try {
            String body = MODEL_JSON.writeValueAsString(Map.of(
                    "choices", new Object[]{Map.of("message", Map.of("content", content))}
            ));
            return new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(body);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class DeterministicModelDispatcher extends Dispatcher {

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String requestBody = request.getBody().readUtf8();
            if (requestBody.contains("FactualityVerifier")) {
                FACTUALITY_MODEL_CALLS.incrementAndGet();
                return modelResponse("{\"factualityScore\":0.96,\"factualErrors\":[],\"hallucinationLog\":[]}");
            }
            if (requestBody.contains("CriticAgent")) {
                CRITIC_MODEL_CALLS.incrementAndGet();
                return modelResponse("{\"verdict\":\"PASS\",\"critique\":\"Grounded content accepted\"}");
            }
            if (requestBody.contains("学习画像抽取智能体")) {
                PROFILE_MODEL_CALLS.incrementAndGet();
                return modelResponse("""
                        {"knowledgeBase":{"value":"CNN basics","confidence":0.8},
                         "goal":{"value":"master padding and stride","confidence":0.9},
                         "cognitiveStyle":{"value":"visual practice","confidence":0.8},
                         "weakPoints":{"value":"stride formula","confidence":0.9},
                         "errorPatterns":{"value":"forgets floor","confidence":0.7},
                         "learningPace":{"value":"steady","confidence":0.7},
                         "emotionAttention":{"value":"focused","confidence":0.8}}
                        """);
            }

            SPECIALIST_MODEL_CALLS.incrementAndGet();
            Matcher citationMatcher = CITATION_ID.matcher(requestBody);
            String citation = citationMatcher.find() ? citationMatcher.group() : "cite-unavailable";
            return modelResponse("""
                    # Personalized CNN resource

                    Padding preserves boundary information, while stride controls the sliding step and output size.
                    Use the convolution output-size formula and verify the result with a small PyTorch experiment.

                    Evidence: [%s]
                    """.formatted(citation));
        }
    }
}

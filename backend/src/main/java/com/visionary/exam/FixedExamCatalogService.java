package com.visionary.exam;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.visionary.exam.FixedExamDtos.PaperSummary;
import static com.visionary.exam.FixedExamDtos.PaperView;
import static com.visionary.exam.FixedExamDtos.QuestionView;

@Service
public class FixedExamCatalogService {

    private static final String CATALOG_RESOURCE = "exams/fixed-exams-v1.json";
    private static final BigDecimal EXPECTED_PAPER_SCORE = new BigDecimal("100.0");

    private final ObjectMapper objectMapper;
    private FixedExamCatalog catalog;

    public FixedExamCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void initialize() {
        this.catalog = readCatalog();
        validateCatalog(this.catalog);
    }

    public String version() {
        return requireCatalog().version();
    }

    public String reviewStatus() {
        return requireCatalog().reviewStatus();
    }

    public List<PaperSummary> listPapers() {
        return requireCatalog().papers().stream().map(this::toSummary).toList();
    }

    public PaperView paperView(String code) {
        FixedExamCatalog.Paper paper = requirePaper(code);
        return new PaperView(
                toSummary(paper),
                paper.questions().stream()
                        .map(question -> new QuestionView(
                                question.id(),
                                question.order(),
                                question.type(),
                                question.difficulty(),
                                List.copyOf(question.knowledgePoints()),
                                question.prompt(),
                                List.copyOf(question.subQuestions()),
                                List.copyOf(question.options()),
                                question.maxScore()
                        ))
                        .toList()
        );
    }

    public FixedExamCatalog.Paper requirePaper(String code) {
        String normalized = normalize(code);
        return requireCatalog().papers().stream()
                .filter(paper -> normalize(paper.code()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "固定题卷不存在"));
    }

    public FixedExamCatalog.Question requireQuestion(String paperCode, String questionId) {
        FixedExamCatalog.Paper paper = requirePaper(paperCode);
        String normalized = normalize(questionId);
        return paper.questions().stream()
                .filter(question -> normalize(question.id()).equals(normalized))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "固定题目不存在"));
    }

    FixedExamCatalog readCatalog() {
        try (var input = new ClassPathResource(CATALOG_RESOURCE).getInputStream()) {
            return objectMapper.readValue(input, FixedExamCatalog.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load fixed exam catalog", exception);
        }
    }

    public void validateCatalog(FixedExamCatalog candidate) {
        require(candidate != null, "catalog is required");
        require(nonBlank(candidate.version()), "catalog version is required");
        require(nonBlank(candidate.reviewStatus()), "catalog review status is required");
        require(candidate.papers() != null && candidate.papers().size() == 5, "catalog must contain exactly 5 papers");

        Set<String> paperCodes = new HashSet<>();
        Set<String> questionIds = new HashSet<>();
        for (FixedExamCatalog.Paper paper : candidate.papers()) {
            require(nonBlank(paper.code()) && paperCodes.add(normalize(paper.code())), "paper code must be unique");
            require(nonBlank(paper.title()), "paper title is required");
            require(nonBlank(paper.topic()), "paper topic is required");
            require(paper.durationMinutes() != null && paper.durationMinutes() > 0, "paper duration must be positive");
            require(paper.questions() != null && paper.questions().size() >= 8 && paper.questions().size() <= 12,
                    "each paper must contain 8 to 12 questions");
            BigDecimal paperScore = BigDecimal.ZERO;
            Set<Integer> orders = new HashSet<>();
            for (FixedExamCatalog.Question question : paper.questions()) {
                validateQuestion(question, questionIds, orders);
                paperScore = paperScore.add(question.maxScore());
            }
            require(paperScore.compareTo(EXPECTED_PAPER_SCORE) == 0, "each paper must total 100 points");
        }
    }

    private void validateQuestion(
            FixedExamCatalog.Question question,
            Set<String> questionIds,
            Set<Integer> orders
    ) {
        require(question != null, "question is required");
        require(nonBlank(question.id()) && questionIds.add(normalize(question.id())), "question id must be globally unique");
        require(question.order() != null && question.order() > 0 && orders.add(question.order()), "question order must be unique");
        require(nonBlank(question.type()), "question type is required");
        require(nonBlank(question.difficulty()), "question difficulty is required");
        require(question.knowledgePoints() != null && !question.knowledgePoints().isEmpty(), "knowledge points are required");
        require(nonBlank(question.prompt()), "question prompt is required");
        require(nonBlank(question.standardAnswer()), "standard answer is required");
        require(nonBlank(question.explanation()), "detailed explanation is required");
        require(question.commonErrors() != null && !question.commonErrors().isEmpty(), "common errors are required");
        require(nonBlank(question.recommendedReview()), "recommended review is required");
        require(nonBlank(question.validationMethod()), "validation method is required");
        require(question.sources() != null && !question.sources().isEmpty(), "reference source is required");
        for (FixedExamCatalog.Source source : question.sources()) {
            require(nonBlank(source.title()) && isHttpUrl(source.url()), "source title and valid URL are required");
        }
        require(question.maxScore() != null && question.maxScore().compareTo(BigDecimal.ZERO) > 0, "max score must be positive");
        require(question.scoringPoints() != null && !question.scoringPoints().isEmpty(), "scoring points are required");
        BigDecimal scoringTotal = BigDecimal.ZERO;
        for (FixedExamCatalog.ScoringPoint point : question.scoringPoints()) {
            require(nonBlank(point.id()) && nonBlank(point.description()), "scoring point id and description are required");
            require(point.points() != null && point.points().compareTo(BigDecimal.ZERO) > 0, "scoring point value must be positive");
            require(point.acceptedKeywords() != null && !point.acceptedKeywords().isEmpty(), "scoring keywords are required");
            scoringTotal = scoringTotal.add(point.points());
        }
        require(scoringTotal.setScale(2, RoundingMode.HALF_UP).compareTo(question.maxScore().setScale(2, RoundingMode.HALF_UP)) == 0,
                "scoring points must add up to max score");

        if ("SINGLE_CHOICE".equals(question.type())) {
            require(question.options() != null && question.options().size() >= 2, "choice question options are required");
            require(question.options().stream().anyMatch(option -> option.key().equalsIgnoreCase(question.standardAnswer())),
                    "choice answer must reference an existing option");
        }
        if (question.type().startsWith("CODE_")) {
            require(question.testCases() != null && !question.testCases().isEmpty(), "code question test cases are required");
        }
    }

    private PaperSummary toSummary(FixedExamCatalog.Paper paper) {
        BigDecimal maxScore = paper.questions().stream()
                .map(FixedExamCatalog.Question::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaperSummary(
                paper.code(),
                paper.title(),
                paper.description(),
                paper.topic(),
                paper.difficulty(),
                paper.durationMinutes(),
                paper.questions().size(),
                maxScore,
                version(),
                reviewStatus()
        );
    }

    private FixedExamCatalog requireCatalog() {
        if (catalog == null) {
            initialize();
        }
        return catalog;
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid fixed exam catalog: " + message);
        }
    }
}

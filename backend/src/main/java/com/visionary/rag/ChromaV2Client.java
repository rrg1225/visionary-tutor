package com.visionary.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.config.VectorDbConfig;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "vector.db", name = "chroma-api-version", havingValue = "V2")
public class ChromaV2Client {

    private final VectorDbConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private volatile String collectionId;

    public ChromaV2Client(VectorDbConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.getConnectTimeoutSeconds())))
                .build();
    }

    public List<VectorDbService.KnowledgeFragment> query(
            Embedding queryEmbedding,
            int topK,
            Set<String> allowedLayers
    ) {
        String id = resolveCollectionId();
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode queryEmbeddings = body.putArray("query_embeddings");
        queryEmbeddings.add(toJsonArray(queryEmbedding.vector()));
        body.put("n_results", Math.max(1, topK));

        ObjectNode where = buildLayerWhere(allowedLayers);
        if (where != null) {
            body.set("where", where);
        }

        ArrayNode include = body.putArray("include");
        include.add("documents");
        include.add("metadatas");
        include.add("distances");

        JsonNode response = postJson(collectionPath(id) + "/query", body);
        return parseQueryResult(response);
    }

    public void deleteByMetadataEquals(String key, String value) {
        String id = resolveCollectionId();
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode where = objectMapper.createObjectNode();
        ObjectNode equals = objectMapper.createObjectNode();
        equals.put("$eq", value);
        where.set(key, equals);
        body.set("where", where);
        postJson(collectionPath(id) + "/delete", body);
    }

    public void upsert(Document document, Embedding embedding) {
        String id = resolveCollectionId();
        ObjectNode body = objectMapper.createObjectNode();
        String vectorId = document.metadata().getString("vector_id");
        body.putArray("ids").add(vectorId == null || vectorId.isBlank() ? UUID.randomUUID().toString() : vectorId);

        ArrayNode embeddings = body.putArray("embeddings");
        embeddings.add(toJsonArray(embedding.vector()));

        body.putArray("documents").add(document.text());

        ArrayNode metadatas = body.putArray("metadatas");
        metadatas.add(metadataToJson(document.metadata()));

        postJson(collectionPath(id) + "/upsert", body);
    }

    public boolean isAvailable() {
        if (!config.isEnabled() || !config.isConfigured() || !config.isChroma() || !config.isChromaApiV2()) {
            return false;
        }
        try {
            resolveCollectionId();
            return true;
        } catch (Exception e) {
            log.debug("Chroma V2 collection is unavailable: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Reads the global collection JSON from Chroma V2 (includes {@code dimension} and {@code metadata}).
     */
    public JsonNode fetchCollectionMetadata() {
        return getJson(collectionByNamePath());
    }

    /**
     * Ensures the configured collection exists; creates an empty one for local dev when missing.
     */
    public JsonNode ensureCollectionExists() {
        try {
            return fetchCollectionMetadata();
        } catch (IllegalStateException ex) {
            if (!isCollectionMissing(ex)) {
                throw ex;
            }
            log.warn(
                    "[vector-db] Chroma collection '{}' 不存在，正在自动创建空集合（请稍后运行 document_processor.py 入库）",
                    config.getCollectionName()
            );
            JsonNode created = createCollection();
            collectionId = created.path("id").asText(null);
            return created;
        }
    }

    private JsonNode createCollection() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", config.getCollectionName());
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("vector_dimension", String.valueOf(config.getVectorDimension()));
        metadata.put("embedding_model", config.getEmbeddingModel());
        metadata.put("embedding_provider", config.getEmbeddingProvider());
        body.set("metadata", metadata);
        body.put("get_or_create", true);
        return postJson(baseTenantDatabasePath() + "/collections", body);
    }

    public static boolean isCollectionMissing(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && (message.contains("InvalidCollection")
                    || message.contains("does not exist")
                    || message.contains("NotFoundError"))) {
                return true;
            }
        }
        return false;
    }

    private String resolveCollectionId() {
        String cached = collectionId;
        if (cached != null && !cached.isBlank()) {
            return cached;
        }

        try {
            JsonNode collection = getJson(collectionByNamePath());
            return cacheCollectionId(collection);
        } catch (IllegalStateException ex) {
            if (isCollectionMissing(ex)) {
                JsonNode created = createCollection();
                return cacheCollectionId(created);
            }
            throw ex;
        }
    }

    private String cacheCollectionId(JsonNode collection) {
        JsonNode idNode = collection.get("id");
        if (idNode == null || idNode.asText().isBlank()) {
            throw new IllegalStateException("Chroma V2 collection has no id: " + config.getCollectionName());
        }
        collectionId = idNode.asText();
        return collectionId;
    }

    private List<VectorDbService.KnowledgeFragment> parseQueryResult(JsonNode response) {
        List<VectorDbService.KnowledgeFragment> fragments = new ArrayList<>();
        JsonNode documents = firstBatch(response.path("documents"));
        JsonNode metadatas = firstBatch(response.path("metadatas"));
        JsonNode distances = firstBatch(response.path("distances"));
        JsonNode ids = firstBatch(response.path("ids"));

        if (!documents.isArray()) {
            return fragments;
        }

        for (int i = 0; i < documents.size(); i++) {
            JsonNode metadata = metadatas.path(i);
            Double distance = distances.path(i).isNumber() ? distances.path(i).asDouble() : null;
            fragments.add(new VectorDbService.KnowledgeFragment(
                    documents.path(i).asText(""),
                    metadataString(metadata, "category", ""),
                    metadataString(metadata, "source", "unknown"),
                    distanceToScore(distance),
                    metadataString(metadata, "chunk_type", ""),
                    metadataString(metadata, "image_path", ""),
                    metadataString(metadata, "layer", ""),
                    metadataString(metadata, "chroma_layer", ""),
                    ids.path(i).asText(metadataString(metadata, "vector_id", "")),
                    metadataString(metadata, "chunk_id", ""),
                    metadataString(metadata, "source_path", metadataString(metadata, "full_path", "")),
                    metadataInteger(metadata, "chunk_index"),
                    metadataInteger(metadata, "chunk_start"),
                    metadataInteger(metadata, "chunk_end")
            ));
        }
        return fragments;
    }

    private JsonNode firstBatch(JsonNode node) {
        if (node.isArray() && !node.isEmpty() && node.get(0).isArray()) {
            return node.get(0);
        }
        return objectMapper.createArrayNode();
    }

    private double distanceToScore(Double distance) {
        if (distance == null) {
            return 1.0;
        }
        return 1.0 / (1.0 + Math.max(0.0, distance));
    }

    private ObjectNode buildLayerWhere(Set<String> allowedLayers) {
        if (allowedLayers == null || allowedLayers.isEmpty()) {
            return null;
        }
        ArrayNode clauses = objectMapper.createArrayNode();
        for (String layer : allowedLayers) {
            if (layer == null || layer.isBlank()) {
                continue;
            }
            clauses.add(equalsClause("layer", layer));
            clauses.add(equalsClause("chroma_layer", layer));
        }
        if (clauses.isEmpty()) {
            return null;
        }
        ObjectNode where = objectMapper.createObjectNode();
        where.set("$or", clauses);
        return where;
    }

    private ObjectNode equalsClause(String key, String value) {
        ObjectNode clause = objectMapper.createObjectNode();
        ObjectNode equals = objectMapper.createObjectNode();
        equals.put("$eq", value);
        clause.set(key, equals);
        return clause;
    }

    private ArrayNode toJsonArray(float[] vector) {
        ArrayNode array = objectMapper.createArrayNode();
        for (float value : vector) {
            array.add(value);
        }
        return array;
    }

    private ObjectNode metadataToJson(Metadata metadata) {
        ObjectNode node = objectMapper.createObjectNode();
        putMetadata(node, metadata, "source");
        putMetadata(node, metadata, "source_path");
        putMetadata(node, metadata, "category");
        putMetadata(node, metadata, "full_path");
        putMetadata(node, metadata, "chunk_id");
        putMetadata(node, metadata, "chunk_index");
        putMetadata(node, metadata, "chunk_start");
        putMetadata(node, metadata, "chunk_end");
        putMetadata(node, metadata, "chunk_type");
        putMetadata(node, metadata, "image_path");
        putMetadata(node, metadata, "layer");
        putMetadata(node, metadata, "chroma_layer");
        putMetadata(node, metadata, "artifact_id");
        putMetadata(node, metadata, "artifact_type");
        putMetadata(node, metadata, "learning_session_id");
        putMetadata(node, metadata, "run_id");
        putMetadata(node, metadata, "vector_id");
        putMetadata(node, metadata, "textbook_id");
        putMetadata(node, metadata, "owner_user_id");
        putMetadata(node, metadata, "title");
        putMetadata(node, metadata, "subject_tag");
        putMetadata(node, metadata, "visibility");
        putMetadata(node, metadata, "review_status");
        return node;
    }

    private void putMetadata(ObjectNode node, Metadata metadata, String key) {
        String value = metadata.getString(key);
        if (value != null) {
            node.put(key, value);
        }
    }

    private String metadataString(JsonNode metadata, String key, String defaultValue) {
        JsonNode value = metadata.path(key);
        return value.isMissingNode() || value.isNull() ? defaultValue : value.asText(defaultValue);
    }

    private Integer metadataInteger(JsonNode metadata, String key) {
        JsonNode value = metadata.path(key);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        String text = value.asText("");
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode getJson(String path) {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(Math.max(1, config.getReadTimeoutSeconds())))
                .GET()
                .build();
        return send(request);
    }

    private JsonNode postJson(String path, JsonNode body) {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(Math.max(1, config.getReadTimeoutSeconds())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return send(request);
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Chroma V2 HTTP " + response.statusCode() + ": " + response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Chroma V2 request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chroma V2 request interrupted: " + request.uri(), e);
        }
    }

    private URI uri(String path) {
        return URI.create(config.getChromaBaseUrl() + path);
    }

    private String collectionPath(String id) {
        return baseTenantDatabasePath() + "/collections/" + encode(id);
    }

    private String collectionByNamePath() {
        return baseTenantDatabasePath() + "/collections/" + encode(config.getCollectionName());
    }

    private String baseTenantDatabasePath() {
        return "/api/v2/tenants/" + encode(config.getTenantName())
                + "/databases/" + encode(config.getDatabaseName());
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

package com.visionary.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

/**
 * Accepts either a string or structured JSON for prompt snapshot fields.
 * Structured values are preserved as compact JSON instead of failing the
 * entire request with a Jackson type-mismatch response.
 */
public class FlexibleStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_STRING) {
            return parser.getValueAsString();
        }
        JsonNode node = parser.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.asText() : node.toString();
    }
}

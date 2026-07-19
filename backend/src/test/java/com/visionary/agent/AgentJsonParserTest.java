package com.visionary.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentJsonParserTest {

    private final AgentJsonParser parser = new AgentJsonParser(new ObjectMapper());

    @Test
    void acceptsOnlyRegisteredStructuredReActDecisions() {
        AgentJsonParser.ReActDecision decision = parser.parseReActDecision(
                "{\"thought\":\"Need evidence\",\"action\":\"RAGRetrieveTool\",\"action_input\":{\"query\":\"CNN\"}}",
                Set.of("RAGRetrieveTool")
        );
        assertEquals("RAGRetrieveTool", decision.action());
        assertEquals("CNN", decision.actionInput().path("query").asText());
    }

    @Test
    void rejectsMalformedMissingAndUnregisteredActionsBeforeToolDispatch() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseReActDecision("not json", Set.of("RAGRetrieveTool")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseReActDecision(
                        "{\"thought\":\"x\",\"action\":\"DeleteEverything\",\"action_input\":{}}",
                        Set.of("RAGRetrieveTool")));
        assertThrows(IllegalArgumentException.class,
                () -> parser.parseReActDecision(
                        "{\"thought\":\"x\",\"action\":\"RAGRetrieveTool\",\"action_input\":\"CNN\"}",
                        Set.of("RAGRetrieveTool")));
    }

    @Test
    void repairsCommonModelJsonAndStopsAtFirstBalancedObject() {
        var repaired = parser.parseLenient("结果如下：```json\\n{ocrText：“x”, confidence:0.8,}\\n``` 后续说明");
        assertEquals("x", repaired.path("ocrText").asText());
        assertEquals(0.8, repaired.path("confidence").asDouble(), 0.001);

        var balanced = parser.parseLenient("prefix {\"a\":1} trailing {not json}");
        assertEquals(1, balanced.path("a").asInt());
    }
}

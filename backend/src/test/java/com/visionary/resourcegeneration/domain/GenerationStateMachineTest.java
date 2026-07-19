package com.visionary.resourcegeneration.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationStateMachineTest {

    private final GenerationStateMachine stateMachine = new GenerationStateMachine();

    @Test
    void acceptsHappyPathAndCriticRevisionLoop() {
        assertTrue(stateMachine.canTransition(null, GenerationState.CREATED));
        assertTrue(stateMachine.canTransition(GenerationState.CREATED, GenerationState.PLANNING));
        assertTrue(stateMachine.canTransition(GenerationState.GENERATING, GenerationState.CRITIQUING));
        assertTrue(stateMachine.canTransition(GenerationState.CRITIQUING, GenerationState.REVISING));
        assertTrue(stateMachine.canTransition(GenerationState.REVISING, GenerationState.CRITIQUING));
        assertTrue(stateMachine.canTransition(GenerationState.PERSISTING, GenerationState.SUCCEEDED));
    }

    @Test
    void allowsExplicitDegradedAndFailureTransitions() {
        assertTrue(stateMachine.canTransition(GenerationState.PLANNING, GenerationState.DEGRADED));
        assertTrue(stateMachine.canTransition(GenerationState.GENERATING, GenerationState.FAILED));
        assertTrue(stateMachine.canTransition(GenerationState.CRITIQUING, GenerationState.MANUAL_REVIEW));
        assertTrue(stateMachine.canTransition(GenerationState.DEGRADED, GenerationState.PERSISTING));
    }

    @Test
    void rejectsSkippingRequiredPhasesAndLeavingTerminalState() {
        assertFalse(stateMachine.canTransition(GenerationState.CREATED, GenerationState.SUCCEEDED));
        assertFalse(stateMachine.canTransition(GenerationState.SUCCEEDED, GenerationState.PLANNING));
        assertThrows(IllegalStateException.class,
                () -> stateMachine.requireTransition(GenerationState.PLANNING, GenerationState.SUCCEEDED));
        assertDoesNotThrow(
                () -> stateMachine.requireTransition(GenerationState.PLANNING, GenerationState.RETRIEVING));
    }

    @Test
    void terminalStateContractIsExplicit() {
        assertTrue(GenerationState.SUCCEEDED.isTerminal());
        assertTrue(GenerationState.MANUAL_REVIEW.isTerminal());
        assertTrue(GenerationState.FAILED.isTerminal());
        assertFalse(GenerationState.CREATED.isTerminal());
        assertFalse(GenerationState.DEGRADED.isTerminal());
    }
}

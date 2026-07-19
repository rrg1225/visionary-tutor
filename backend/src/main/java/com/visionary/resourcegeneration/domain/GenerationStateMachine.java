package com.visionary.resourcegeneration.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class GenerationStateMachine {

    private static final Map<GenerationState, Set<GenerationState>> TRANSITIONS = transitions();

    public boolean canTransition(GenerationState from, GenerationState to) {
        if (from == null) {
            return to == GenerationState.CREATED;
        }
        if (from == to) {
            return false;
        }
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void requireTransition(GenerationState from, GenerationState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Illegal generation state transition: " + from + " -> " + to);
        }
    }

    private static Map<GenerationState, Set<GenerationState>> transitions() {
        Map<GenerationState, Set<GenerationState>> transitions = new EnumMap<>(GenerationState.class);
        transitions.put(GenerationState.CREATED, next(GenerationState.PLANNING));
        transitions.put(GenerationState.PLANNING, next(GenerationState.RETRIEVING));
        transitions.put(GenerationState.RETRIEVING, next(GenerationState.GENERATING));
        transitions.put(GenerationState.GENERATING,
                next(GenerationState.CRITIQUING, GenerationState.PERSISTING));
        transitions.put(GenerationState.CRITIQUING,
                next(GenerationState.REVISING, GenerationState.PERSISTING));
        transitions.put(GenerationState.REVISING,
                next(GenerationState.CRITIQUING, GenerationState.PERSISTING));
        transitions.put(GenerationState.PERSISTING, next(GenerationState.SUCCEEDED));
        transitions.put(GenerationState.DEGRADED,
                next(GenerationState.GENERATING, GenerationState.PERSISTING));
        return Map.copyOf(transitions);
    }

    private static Set<GenerationState> next(GenerationState... normalStates) {
        EnumSet<GenerationState> states = EnumSet.noneOf(GenerationState.class);
        states.addAll(Set.of(normalStates));
        states.add(GenerationState.DEGRADED);
        states.add(GenerationState.MANUAL_REVIEW);
        states.add(GenerationState.FAILED);
        return Set.copyOf(states);
    }
}

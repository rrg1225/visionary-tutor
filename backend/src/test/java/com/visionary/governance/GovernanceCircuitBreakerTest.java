package com.visionary.governance;

import com.visionary.config.GovernanceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GovernanceCircuitBreakerTest {

    private GovernanceProperties properties;
    private GovernanceCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        properties = new GovernanceProperties();
        properties.setMaxRevisionRounds(5);
        properties.setDeltaThreshold(3.0);
        properties.setConvergenceDeltaThreshold(0.05);
        breaker = new GovernanceCircuitBreaker(properties);
    }

    @Test
    void evaluate_shouldHaltWhenDeltaNotPositive() {
        GovernanceCircuitBreaker.BreakerDecision decision = breaker.evaluate(1, 72.0, 75.0);

        assertThat(decision.shouldContinue()).isFalse();
        assertThat(decision.decisionCode()).isEqualTo(GovernanceCircuitBreaker.CODE_HALT_NEGATIVE_DELTA);
    }

    @Test
    void evaluate_shouldHaltWhenMaxRoundsReached() {
        GovernanceCircuitBreaker.BreakerDecision decision = breaker.evaluate(5, 90.0, 80.0);

        assertThat(decision.shouldContinue()).isFalse();
        assertThat(decision.decisionCode()).isEqualTo(GovernanceCircuitBreaker.CODE_HALT_MAX_ROUND);
    }

    @Test
    void evaluate_shouldContinueWhenWithinRoundsAndGainSufficient() {
        GovernanceCircuitBreaker.BreakerDecision decision = breaker.evaluate(1, 85.0, 75.0);

        assertThat(decision.shouldContinue()).isTrue();
        assertThat(decision.decisionCode()).isEqualTo(GovernanceCircuitBreaker.CODE_CONTINUE);
    }

    @Test
    void evaluate_shouldHaltOnConvergenceWhenTwoLowGains() {
        GovernanceCircuitBreaker.BreakerDecision decision = breaker.evaluate(2, 82.0, 80.0, 2.0);

        assertThat(decision.shouldContinue()).isFalse();
        assertThat(decision.decisionCode()).isEqualTo(GovernanceCircuitBreaker.CODE_HALT_CONVERGENCE);
    }

    @Test
    void evaluate_shouldContinueWhenOnlyOneLowGain() {
        GovernanceCircuitBreaker.BreakerDecision decision = breaker.evaluate(2, 90.0, 80.0, 2.0);

        assertThat(decision.shouldContinue()).isTrue();
        assertThat(decision.decisionCode()).isEqualTo(GovernanceCircuitBreaker.CODE_CONTINUE);
    }
}

package com.visionary.entity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AgentExecutionLogMigrationTest {

    @Test
    void governanceFieldsAreBackedByFlywayColumns() throws Exception {
        String migration = new ClassPathResource(
                "db/migration/V19__agent_execution_log_governance_columns.sql"
        ).getContentAsString(StandardCharsets.UTF_8);

        assertThat(migration).contains(
                "artifact_type",
                "fallback_reason",
                "revision_round",
                "max_revision_rounds",
                "revision_status",
                "reflection_reason",
                "critic_verdict",
                "factuality_score"
        );
    }
}

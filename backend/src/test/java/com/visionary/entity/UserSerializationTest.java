package com.visionary.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSerializationTest {

    @Test
    void passwordHashIsNeverSerializedToApiJson() throws Exception {
        User user = new User();
        user.setUsername("learner");
        user.setPassword("$2a$10$must-not-leak");

        String json = new ObjectMapper().writeValueAsString(user);

        assertTrue(json.contains("\"username\":\"learner\""));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("must-not-leak"));
    }
}


package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SandboxExecuteRequest {

    @NotBlank(message = "code is required")
    private String code;
}

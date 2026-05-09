package com.agenticnpc.gateway.model;

import com.agenticnpc.model.ActionParameters;
import com.agenticnpc.model.ActionType;

public record ValidationResult(
    boolean          valid,
    ActionType       actionType,
    ActionParameters parameters,
    String           failReason
) {
    public static ValidationResult valid(ActionType type, ActionParameters params) {
        return new ValidationResult(true, type, params, null);
    }

    public static ValidationResult invalid(String reason) {
        return new ValidationResult(false, ActionType.NONE, null, reason);
    }
}

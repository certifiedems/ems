package com.ems.enums;

public enum QuestionCategory {
    TECHNICAL,
    FUNCTIONAL,
    COMPLIANCE,
    SAFETY,
    GENERAL;

    public String databaseValue() {
        return switch (this) {
            case TECHNICAL -> "Technical";
            case FUNCTIONAL -> "Functional";
            case COMPLIANCE -> "Compliance";
            case SAFETY -> "Safety";
            case GENERAL -> "General";
        };
    }
}

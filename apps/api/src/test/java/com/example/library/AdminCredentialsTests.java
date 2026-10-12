package com.example.library;

import com.example.library.common.AdminCredentials;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdminCredentialsTests {
    @Test void rejectsInvalidUsernameAndEmptyOrOutOfRangePassword() {
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("a!", "a".repeat(5)));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", null));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", ""));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", " ".repeat(5)));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", "a".repeat(4)));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", "a".repeat(129)));
    }

    @Test void usesSameLengthBoundariesAsBackendPasswordValidation() {
        assertDoesNotThrow(() -> AdminCredentials.validate("admin", "a".repeat(5)));
        assertDoesNotThrow(() -> AdminCredentials.validate("admin", "a".repeat(128)));
        assertDoesNotThrow(() -> AdminCredentials.validate("admin", "🙂".repeat(5)));
        assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", "🙂".repeat(65)));
    }

    @Test void errorMessagesNeverEchoInput() {
        var usernameError = assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validateUsername("private!input"));
        assertFalse(usernameError.getMessage().contains("private!input"));
        var passwordError = assertThrows(IllegalArgumentException.class, () -> AdminCredentials.validate("admin", "tiny"));
        assertFalse(passwordError.getMessage().contains("tiny"));
    }
}

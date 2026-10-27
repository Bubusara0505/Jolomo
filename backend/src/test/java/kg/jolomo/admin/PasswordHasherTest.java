package kg.jolomo.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1000);

    @Test
    void verifiesRightAndRejectsWrongPassword() {
        String stored = hasher.hash("correct horse battery");

        assertThat(hasher.verify("correct horse battery", stored)).isTrue();
        assertThat(hasher.verify("correct horse batterY", stored)).isFalse();
        assertThat(hasher.verify("", stored)).isFalse();
    }

    @Test
    void saltsDifferAndPasswordIsNotInTheRecord() {
        String a = hasher.hash("same-password-123");
        String b = hasher.hash("same-password-123");

        assertThat(a).isNotEqualTo(b).doesNotContain("same-password-123").startsWith("pbkdf2-sha256$1000$");
    }

    @Test
    void garbageRecordIsNeverAMatch() {
        assertThat(hasher.verify("x", "not-a-hash")).isFalse();
        assertThat(hasher.verify("x", "pbkdf2-sha256$abc$!!$!!")).isFalse();
    }
}

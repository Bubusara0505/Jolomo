package kg.jolomo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SP02-04: флаги cookie по ТЗ. */
class SessionCookiesTest {

    private final SessionCookies cookies = new SessionCookies(
            new SessionSettings("test-session-secret-0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(30), true));

    private static String find(List<String> all, String name) {
        return all.stream().filter(c -> c.startsWith(name + "=")).findFirst().orElseThrow();
    }

    @Test
    void accessAndRefreshAreHttpOnlySecureLax() {
        List<String> set = cookies.issue(new SessionService.Tokens(
                UUID.randomUUID(), "acc", "ref", Duration.ofMinutes(15), Duration.ofDays(30)));

        for (String name : List.of("jl_access", "jl_refresh")) {
            assertThat(find(set, name)).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        }
        assertThat(find(set, "jl_access")).contains("Max-Age=900").contains("Path=/");
        assertThat(find(set, "jl_refresh")).contains("Path=/api/v1/auth");
    }

    @Test
    void csrfCookieIsReadableByScriptButSecureLax() {
        String csrf = find(cookies.issue(new SessionService.Tokens(
                UUID.randomUUID(), "acc", "ref", Duration.ofMinutes(15), Duration.ofDays(30))), "jl_csrf");

        assertThat(csrf).doesNotContain("HttpOnly").contains("Secure").contains("SameSite=Lax");
    }

    @Test
    void clearExpiresAllThree() {
        List<String> cleared = cookies.clear();

        assertThat(cleared).hasSize(3).allSatisfy(c -> assertThat(c).contains("Max-Age=0"));
    }
}

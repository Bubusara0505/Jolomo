package kg.jolomo.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import kg.jolomo.shared.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF для мутаций, пришедших с cookie сессии (double submit): заголовок X-CSRF-Token должен совпасть
 * с cookie jl_csrf. Запросы без cookie сессии (вход по телефону) проверку не требуют.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class CsrfFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE.contains(request.getMethod()) && hasSessionCookie(request) && !csrfValid(request)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean hasSessionCookie(HttpServletRequest request) {
        return SessionCookies.read(request, SessionCookies.ACCESS).isPresent()
                || SessionCookies.read(request, SessionCookies.REFRESH).isPresent();
    }

    private static boolean csrfValid(HttpServletRequest request) {
        String header = request.getHeader(SessionCookies.CSRF_HEADER);
        String cookie = SessionCookies.read(request, SessionCookies.CSRF).orElse(null);
        return header != null
                && cookie != null
                && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8));
    }

    /** Фильтр стоит до MVC, поэтому конверт ошибки пишем здесь же, в том же формате. */
    private static void reject(HttpServletResponse response) throws IOException {
        String cid = MDC.get(CorrelationIdFilter.MDC_KEY);
        String json = "{\"code\":\"CSRF_INVALID\",\"message\":\"error.csrf\",\"correlationId\":"
                + (cid == null ? "null" : "\"" + cid + "\"") + "}";
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json);
    }
}

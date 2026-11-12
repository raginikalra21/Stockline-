package in.stockline.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

public final class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-ID";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID correlationId = parse(request.getHeader(HEADER));
        if (correlationId == null) correlationId = UUID.randomUUID();
        UUID previous = CorrelationContext.replace(correlationId);
        String previousMdc = MDC.get("correlation_id");
        MDC.put("correlation_id", correlationId.toString());
        response.setHeader(HEADER, correlationId.toString());
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            if (response.getStatus() >= 500) {
                log.error("HTTP request failed with status {} after {} ms", response.getStatus(), elapsedMillis);
            } else if (response.getStatus() >= 400) {
                log.debug("HTTP request completed with status {} after {} ms", response.getStatus(), elapsedMillis);
            } else {
                log.debug("HTTP request completed with status {} after {} ms", response.getStatus(), elapsedMillis);
            }
            if (previousMdc == null) MDC.remove("correlation_id"); else MDC.put("correlation_id", previousMdc);
            CorrelationContext.restore(previous);
        }
    }

    private UUID parse(String value) {
        if (value == null || value.length() != 36) return null;
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equalsIgnoreCase(value) ? parsed : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}

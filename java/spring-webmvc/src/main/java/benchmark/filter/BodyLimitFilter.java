package benchmark.filter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Order(4)
public class BodyLimitFilter implements Filter {

    private static final long MAX_BODY_SIZE = 1_048_576; // 1MB

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        if (req.getContentLengthLong() > MAX_BODY_SIZE) {
            res.setStatus(413);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"Request body too large\"}");
            return;
        }

        chain.doFilter(request, response);
    }
}

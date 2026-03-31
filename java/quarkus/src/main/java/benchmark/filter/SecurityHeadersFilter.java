package benchmark.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(300)
public class SecurityHeadersFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().putSingle("X-XSS-Protection", "1; mode=block");
        responseContext.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        responseContext.getHeaders().putSingle("X-Frame-Options", "DENY");
        responseContext.getHeaders().putSingle("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        responseContext.getHeaders().putSingle("Content-Security-Policy", "default-src 'self'");
        responseContext.getHeaders().putSingle("Referrer-Policy", "strict-origin-when-cross-origin");
        responseContext.getHeaders().putSingle("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        responseContext.getHeaders().putSingle("Cross-Origin-Opener-Policy", "same-origin");
    }
}

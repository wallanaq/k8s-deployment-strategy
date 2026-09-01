package com.example.qrcode.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Stamps every response with the Pod that handled it, via the X-Pod-Name
 * header. Useful for observing traffic distribution during rollouts
 * (canary/blue-green); the {@code POD_NAME} env var is only populated in
 * Kubernetes (base-webapp chart's optional podInfo.enabled Downward API
 * injection), so it defaults to "unknown" for local runs.
 */
@Component
public class PodIdentityFilter implements Filter {

    @Value("${POD_NAME:unknown}")
    private String podName;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Pod-Name", podName);
        }
        chain.doFilter(request, response);
    }
}

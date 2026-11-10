package com.boldfit.checkout;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Deliberately limited demo authentication. Tokens identify different customers. */
@Component
public class DemoAuth extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        if (req.getRequestURI().startsWith("/api/") && !req.getRequestURI().equals("/api/products")) {
            String customer = switch (String.valueOf(req.getHeader("Authorization"))) {
                case "Bearer demo-alex" -> "alex";
                case "Bearer demo-sam" -> "sam";
                default -> null;
            };
            if (customer == null) {
                res.setStatus(401); res.setContentType("application/json");
                res.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"A demo bearer token is required.\"}");
                return;
            }
            req.setAttribute("customer",customer);
        }
        chain.doFilter(req,res);
    }
}

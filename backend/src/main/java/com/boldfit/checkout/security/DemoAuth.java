package com.boldfit.checkout.security;

import com.boldfit.checkout.dto.ApiErrorResponse;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Deliberately limited demo authentication. Tokens identify different customers. */
@Component
public class DemoAuth extends OncePerRequestFilter {
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public DemoAuth(com.fasterxml.jackson.databind.ObjectMapper json) {
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/") && !req.getRequestURI().equals("/api/products")) {
      String customer =
          switch (String.valueOf(req.getHeader("Authorization"))) {
            case "Bearer demo-alex" -> "alex";
            case "Bearer demo-sam" -> "sam";
            case "Bearer demo-operator" -> "operator";
            default -> null;
          };
      if (customer == null) {
        res.setStatus(401);
        res.setContentType("application/json");
        json.writeValue(
            res.getWriter(),
            new ApiErrorResponse(
                "UNAUTHORIZED",
                "A demo bearer token is required.",
                RequestCorrelation.id(req),
                java.util.Map.of()));
        return;
      }
      if (req.getRequestURI().startsWith("/api/ops/") && !customer.equals("operator")) {
        res.setStatus(403);
        res.setContentType("application/json");
        json.writeValue(
            res.getWriter(),
            new ApiErrorResponse(
                "FORBIDDEN",
                "An operator identity is required.",
                RequestCorrelation.id(req),
                java.util.Map.of()));
        return;
      }
      req.setAttribute("customer", customer);
    }
    chain.doFilter(req, res);
  }
}

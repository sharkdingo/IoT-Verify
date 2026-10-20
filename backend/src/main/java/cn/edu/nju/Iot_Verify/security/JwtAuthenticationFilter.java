package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;
    private final HandlerExceptionResolver exceptionResolver;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenBlacklistService tokenBlacklistService,
                                   UserRepository userRepository,
                                   @Qualifier("handlerExceptionResolver") @Lazy HandlerExceptionResolver exceptionResolver) {
        this.jwtUtil = jwtUtil;
        this.tokenBlacklistService = tokenBlacklistService;
        this.userRepository = userRepository;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        try {
            String token = extractToken(request);
            JwtUtil.TokenIdentity identity = null;
            if (StringUtils.hasText(token)) {
                try {
                    identity = jwtUtil.parseIdentity(token);
                } catch (JwtException | UnauthorizedException | IllegalArgumentException invalidToken) {
                    // Only invalid credentials become unauthenticated; infrastructure errors remain errors.
                }
            }
            if (identity != null) {
                JwtUtil.TokenIdentity authenticatedIdentity = identity;
                try {
                    if (!tokenBlacklistService.isBlacklisted(token)
                            && userRepository.findById(identity.userId())
                                    .map(user -> user.getAuthVersion() == authenticatedIdentity.authVersion()).orElse(false)) {
                        UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(identity.userId(), null, Collections.emptyList());
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        // Also set UserContextHolder for AI tools
                        UserContextHolder.setUserId(identity.userId());
                    }
                } catch (RuntimeException failure) {
                    if (exceptionResolver.resolveException(request, response, null, failure) == null) throw failure;
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // UserContextHolder is a ThreadLocal on a reused Tomcat worker thread. If we
            // never cleared it, a later request arriving WITHOUT a valid token (so the
            // block above neither sets nor clears it) would still observe the previous
            // request's userId — a cross-user leak on the AI-tool path that reads
            // UserContextHolder. SecurityContextHolder is cleared by Spring Security's own
            // filter; this ThreadLocal is ours to clean up.
            UserContextHolder.clear();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}

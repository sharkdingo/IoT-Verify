package cn.edu.nju.Iot_Verify.security;

import cn.edu.nju.Iot_Verify.dto.Result;
import cn.edu.nju.Iot_Verify.exception.GlobalExceptionHandler;
import cn.edu.nju.Iot_Verify.exception.UnauthorizedException;
import cn.edu.nju.Iot_Verify.repository.UserRepository;
import cn.edu.nju.Iot_Verify.service.TokenBlacklistService;
import cn.edu.nju.Iot_Verify.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthenticationFilterFailureSemanticsTest {
    private final JwtUtil jwt = mock(JwtUtil.class);
    private final UserRepository users = mock(UserRepository.class);
    private final TokenBlacklistService blacklist = mock(TokenBlacklistService.class);

    @AfterEach
    void clearContext() {
        UserContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void databaseReadFailureUsesTheExisting500EnvelopeAndNeverBecomesAnUnauthenticatedRequest() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        HandlerExceptionResolver resolver = mvc.getDispatcherServlet().getWebApplicationContext()
                .getBean("handlerExceptionResolver", HandlerExceptionResolver.class);
        when(jwt.parseIdentity("valid")).thenReturn(new JwtUtil.TokenIdentity(7L, 0L));
        when(users.findById(7L)).thenThrow(new DataAccessResourceFailureException("database unavailable"));
        var response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        new JwtAuthenticationFilter(jwt, blacklist, users, resolver).doFilter(
                request(), response, (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(500, response.getStatus());
        assertEquals("application/json", response.getContentType());
        assertTrue(response.getContentAsString().contains("\"code\":500"));
        assertFalse(response.getContentAsString().contains("database unavailable"));
        assertNull(UserContextHolder.getUserId());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void anUnresolvedInfrastructureFailurePropagatesAndStillClearsTheThreadLocal() {
        var resolver = mock(HandlerExceptionResolver.class);
        when(jwt.parseIdentity("valid")).thenReturn(new JwtUtil.TokenIdentity(7L, 0L));
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(users.findById(7L)).thenThrow(failure);
        UserContextHolder.setUserId(99L);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> new JwtAuthenticationFilter(jwt, blacklist, users, resolver)
                        .doFilter(request(), new MockHttpServletResponse(), (req, res) -> fail("must not continue"))));
        assertNull(UserContextHolder.getUserId());
    }

    @Test
    void invalidTokenContinuesUnauthenticatedWithoutQueryingTheDatabase() throws Exception {
        when(jwt.parseIdentity("valid")).thenThrow(UnauthorizedException.invalidToken());
        AtomicBoolean chainCalled = new AtomicBoolean();

        new JwtAuthenticationFilter(jwt, blacklist, users, mock(HandlerExceptionResolver.class))
                .doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
                    chainCalled.set(true);
                    assertNull(UserContextHolder.getUserId());
                });

        assertTrue(chainCalled.get());
        verifyNoInteractions(users, blacklist);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("GET", "/probe");
        request.addHeader("Authorization", "Bearer valid");
        return request;
    }

    @RestController
    private static class ProbeController {
        @GetMapping("/probe")
        Result<Void> probe() {
            return Result.success();
        }
    }
}

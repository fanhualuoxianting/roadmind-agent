package com.roadmind.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;

class RateLimitFilterTest {

    @Test
    void demoManagementRequestsUseTheUserMessageBucket() throws Exception {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RateLimitProperties properties = new RateLimitProperties();
        properties.setUserMessageLimit(1);
        properties.setWindowSeconds(60);
        RateLimitFilter filter = new RateLimitFilter(properties, new RateLimitService(provider));
        FilterChain chain = mock(FilterChain.class);

        MockHttpServletRequest first = request();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, chain);

        MockHttpServletRequest second = request();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, chain);

        assertThat(firstResponse.getStatus()).isEqualTo(200);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("60");
        verify(chain, times(1)).doFilter(any(), any());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/demo/vehicles/demo-vehicle-001/state");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}

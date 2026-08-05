package com.roadmind.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
@Profile("demo")
public class DemoSecurityConfiguration {

    @Bean
    SecurityFilterChain demoSecurityFilterChain(
            HttpSecurity http,
            SecurityContextRepository contextRepository,
            CsrfTokenRepository csrfTokenRepository,
            SecurityErrorWriter errorWriter) throws Exception {
        return http
                .securityContext(context -> context
                        .securityContextRepository(contextRepository)
                        .requireExplicitSave(true))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers("/internal/v1/tools/**"))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/internal/v1/tools/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.unauthenticated(response))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.forbidden(response, exception)))
                .addFilterBefore(new DemoAuthenticationFilter(contextRepository), AnonymousAuthenticationFilter.class)
                .build();
    }
}

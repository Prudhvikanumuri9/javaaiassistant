package dev.personalassistant.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    UserDetailsService householdUser(@Value("${personalassistant.security.pin:}") String pin) {
        String password = pin.isBlank() ? "disabled" : pin;
        return new InMemoryUserDetailsManager(User.withUsername("home")
                .password("{noop}" + password)
                .roles("HOUSEHOLD")
                .build());
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http,
                                 @Value("${personalassistant.security.pin:}") String pin) throws Exception {
        if (pin.isBlank()) {
            http.authorizeHttpRequests(access -> access.anyRequest().permitAll());
        } else {
            http.authorizeHttpRequests(access -> access.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults());
        }
        // The API is a private same-origin LAN application using browser Basic auth.
        http.csrf(csrf -> csrf.disable());
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; img-src 'self' data:; media-src 'self' blob:; " +
                                "script-src 'self'; style-src 'self'; connect-src 'self'")));
        return http.build();
    }
}

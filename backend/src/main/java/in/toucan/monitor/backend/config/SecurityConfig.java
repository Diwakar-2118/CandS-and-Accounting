package in.toucan.monitor.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Dashboard access control. Roles: VIEWER (read dashboards, export), OPERATOR (also trigger runs and
 * confirm mappings), ADMIN (everything). Users come from monitor.users (env-provided passwords,
 * bcrypt "{bcrypt}..." or "{noop}..." prefixes). If none are configured an admin with a random
 * password is created and its password printed once at startup.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable()) // stateless Basic-auth API consumed by the SPA; no cookies are issued
                .sessionManagement(s -> s.sessionCreationPolicy(
                        org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/runs/**", "/api/mappings/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/processors/**").hasAnyRole("OPERATOR", "ADMIN")
                        .requestMatchers("/api/audit/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").hasAnyRole("VIEWER", "OPERATOR", "ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(MonitorProperties props, PasswordEncoder enc) {
        List<UserDetails> list = new ArrayList<>();
        for (MonitorProperties.User u : props.getUsers()) {
            if (u.getUsername() == null || u.getUsername().isBlank() || u.getPassword() == null
                    || u.getPassword().isBlank()) continue;
            String pw = u.getPassword().startsWith("{") ? u.getPassword() : enc.encode(u.getPassword());
            list.add(User.withUsername(u.getUsername()).password(pw)
                    .roles(u.getRoles().isEmpty() ? new String[]{"VIEWER"} : u.getRoles().toArray(String[]::new)).build());
        }
        if (list.isEmpty()) {
            byte[] b = new byte[18];
            new SecureRandom().nextBytes(b);
            String pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            list.add(User.withUsername("admin").password(enc.encode(pw)).roles("ADMIN").build());
            log.warn("No monitor.users configured. Generated dashboard login admin / {} (set DASH_* env vars)", pw);
        }
        return new InMemoryUserDetailsManager(list);
    }
}

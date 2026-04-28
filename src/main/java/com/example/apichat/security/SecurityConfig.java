package com.example.apichat.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   DaoAuthenticationProvider authenticationProvider,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/", "/index.html", "/login.html", "/user.html", "/expert.html", "/appointment.html", "/admin.html", "/app.css", "/auth/**", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/mcp/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/expert/**").hasRole("EXPERT")
                        .requestMatchers("/ai/**", "/rag/**", "/api/appointments/**", "/api/metrics/**").authenticated()
                        .anyRequest().permitAll())
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(
            @Value("${app.security.users.admin.username:admin}") String adminUsername,
            @Value("${app.security.users.admin.password:123456}") String adminPassword,
            @Value("${app.security.users.user.username:user}") String userUsername,
            @Value("${app.security.users.user.password:123456}") String userPassword,
            @Value("${app.security.users.experts.0.username:linyu}") String expert1Username,
            @Value("${app.security.users.experts.0.password:123456}") String expert1Password,
            @Value("${app.security.users.experts.1.username:chenmo}") String expert2Username,
            @Value("${app.security.users.experts.1.password:123456}") String expert2Password,
            @Value("${app.security.users.experts.2.username:jiangning}") String expert3Username,
            @Value("${app.security.users.experts.2.password:123456}") String expert3Password,
            @Value("${app.security.users.experts.3.username:suwan}") String expert4Username,
            @Value("${app.security.users.experts.3.password:123456}") String expert4Password,
            @Value("${app.security.users.experts.4.username:qiaoan}") String expert5Username,
            @Value("${app.security.users.experts.4.password:123456}") String expert5Password,
            PasswordEncoder passwordEncoder) {
        List<UserDetails> users = new ArrayList<>();
        users.add(User.withUsername(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build());
        users.add(User.withUsername(userUsername)
                .password(passwordEncoder.encode(userPassword))
                .roles("USER")
                .build());
        users.add(expertUser(expert1Username, expert1Password, passwordEncoder));
        users.add(expertUser(expert2Username, expert2Password, passwordEncoder));
        users.add(expertUser(expert3Username, expert3Password, passwordEncoder));
        users.add(expertUser(expert4Username, expert4Password, passwordEncoder));
        users.add(expertUser(expert5Username, expert5Password, passwordEncoder));
        return new InMemoryUserDetailsManager(users);
    }

    private UserDetails expertUser(String username, String password, PasswordEncoder passwordEncoder) {
        return User.withUsername(username)
                .password(passwordEncoder.encode(password))
                .roles("EXPERT")
                .build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(UserDetailsService userDetailsService,
                                                            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}

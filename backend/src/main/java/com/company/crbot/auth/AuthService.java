package com.company.crbot.auth;

import com.company.crbot.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResult login(String username, String password) {
        // 用户不存在与密码错误使用同一文案，避免探测账号
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        // 签发访问令牌
        String token = jwtService.issue(user.getUsername(), user.getRole());
        return new LoginResult(token, user.getUsername(), user.getRole());
    }

    public LoginResult me(String username) {
        // 用登录名回读角色，供前端展示
        UserEntity user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "未登录"));
        return new LoginResult(null, user.getUsername(), user.getRole());
    }

    public record LoginResult(String token, String username, String role) {
    }
}

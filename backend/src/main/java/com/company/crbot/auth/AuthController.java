package com.company.crbot.auth;

import com.company.crbot.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final McpAccessService mcpAccessService;

    public AuthController(AuthService authService, McpAccessService mcpAccessService) {
        this.authService = authService;
        this.mcpAccessService = mcpAccessService;
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        // 校验通过后签发 JWT
        return authService.login(request.username(), request.password());
    }

    @PostMapping("/logout")
    public Map<String, String> logout() {
        // 无状态令牌由前端丢弃；服务端不维护会话
        return Map.of("message", "已登出");
    }

    @GetMapping("/me")
    public AuthService.LoginResult me() {
        // 返回当前登录管理员
        return authService.me(CurrentUser.username());
    }

    @PostMapping("/mcp-token")
    public McpAccessService.IssuedToken mcpToken() {
        // 登录管理员轮换 IDE 令牌；明文只出现在这一次响应里
        return mcpAccessService.issue();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }
}

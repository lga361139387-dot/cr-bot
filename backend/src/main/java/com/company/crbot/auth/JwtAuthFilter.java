package com.company.crbot.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final McpAccessService mcpAccessService;

    public JwtAuthFilter(JwtService jwtService, McpAccessService mcpAccessService) {
        this.jwtService = jwtService;
        this.mcpAccessService = mcpAccessService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        // 没有 Bearer 时交给后续规则：公开接口放行，其余 401
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        try {
            // 管理端 JWT 验签成功后写入登录态
            String username = jwtService.parse(token).getSubject();
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    username, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException ex) {
            // JWT 失败后再认 IDE MCP 令牌，且只放行评审/知识库只读入口
            if (isIdeMcp(request) && mcpAccessService.matches(token)) {
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        "ide-mcp", null, List.of(new SimpleGrantedAuthority("ROLE_MCP")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"登录已失效\"}");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean isIdeMcp(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path = uri == null ? "" : uri;
        // 令牌不能拿去调看板、改知识库或重跑评审
        return path.equals(McpAccessService.ENDPOINT) || path.endsWith(McpAccessService.ENDPOINT);
    }
}

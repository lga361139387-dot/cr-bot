package com.company.crbot.auth;

import com.company.crbot.config.AppProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Order(1)
public class AdminUserInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    public AdminUserInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder, AppProperties properties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 已有用户时不覆盖口令，避免重启把管理员密码打回环境变量
        if (userRepository.count() > 0) {
            return;
        }
        UserEntity user = new UserEntity();
        user.setUsername(properties.getAdminUsername());
        user.setPasswordHash(passwordEncoder.encode(properties.getAdminPassword()));
        user.setRole("ADMIN");
        userRepository.save(user);
    }
}

package com.company.crbot.settings;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
public class SettingsInitializer implements ApplicationRunner {

    private final SettingsService settingsService;

    public SettingsInitializer(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 启动时补齐默认阈值、提示词
        settingsService.seedDefaults();
    }
}

package com.company.crbot.settings;

import com.company.crbot.common.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public SettingsService.SettingsView get() {
        // 密钥只返回是否已配置
        return settingsService.view();
    }

    @PutMapping
    public SettingsService.SettingsView update(@RequestBody SettingsService.SettingsUpdate update) {
        // 记录是谁改了系统设置，不记录密钥
        log.info("更新系统设置 user={}", CurrentUser.username());
        return settingsService.update(update);
    }
}

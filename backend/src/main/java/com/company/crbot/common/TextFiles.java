package com.company.crbot.common;

import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

public final class TextFiles {

    private TextFiles() {
    }

    public static String readUtf8(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        // 一期只收 Markdown / 纯文本
        if (!(name.endsWith(".md") || name.endsWith(".txt") || name.endsWith(".markdown"))) {
            throw new ApiException("只允许上传 md 或 txt 文件");
        }
        try {
            String text = new String(file.getBytes(), StandardCharsets.UTF_8);
            if (text.indexOf('\0') >= 0) {
                throw new ApiException("文件不是文本");
            }
            if (text.isBlank()) {
                throw new ApiException("文件内容为空");
            }
            return text;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException("读取上传文件失败");
        }
    }
}

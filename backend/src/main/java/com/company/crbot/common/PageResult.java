package com.company.crbot.common;

import java.util.List;

public record PageResult<T>(long total, int page, int size, List<T> records) {
}

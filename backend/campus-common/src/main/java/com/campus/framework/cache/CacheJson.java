package com.campus.framework.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 缓存专用 JSON 编解码（阶段 16）。
 *
 * <p>独立 ObjectMapper，与 Web 层序列化规则解耦：</p>
 * <ul>
 *   <li>时间类型统一 ISO 字符串（{@code 2026-10-10} / {@code 20:00:00} / {@code 2026-10-10 20:00:00}），
 *       写进 Redis 可读、可回读——排障时 {@code redis-cli GET} 一眼能看懂</li>
 *   <li>不写时间戳数组（{@code WRITE_DATES_AS_TIMESTAMPS} 关闭）</li>
 * </ul>
 *
 * <p>选择独立实例而不是复用 Spring 容器里的 ObjectMapper：缓存格式属于"存储协议"，
 * 不应随 Web 层序列化定制（如 JacksonConfig 的日期格式）被动改变——否则一次格式调整
 * 就会让存量缓存集体反序列化失败。</p>
 */
public final class CacheJson {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CacheJson() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("缓存序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("缓存反序列化失败: " + e.getMessage(), e);
        }
    }

    public static <T> T read(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("缓存反序列化失败: " + e.getMessage(), e);
        }
    }
}

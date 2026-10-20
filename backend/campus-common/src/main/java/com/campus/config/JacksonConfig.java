package com.campus.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * Jackson 时间序列化配置。
 *
 * <p>约定（全局唯一标准）：</p>
 * <ul>
 *   <li>LocalDateTime 序列化/反序列化格式：{@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>LocalDate：{@code yyyy-MM-dd}</li>
 *   <li>时区：Asia/Shanghai（同时由 application.yml 的 spring.jackson.time-zone 兜底 Date 类型）</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);
        return builder -> builder
                .serializers(new LocalDateTimeSerializer(dateTimeFormatter))
                .serializers(new LocalDateSerializer(dateFormatter))
                .deserializers(new LocalDateTimeDeserializer(dateTimeFormatter))
                .deserializers(new LocalDateDeserializer(dateFormatter));
    }
}

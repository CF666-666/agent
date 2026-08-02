/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 配置
 *
 * <p>
 * 统一配置 Spring MVC 的消息转换器（UTF-8 编码）与静态资源映射（多模态引用图片），
 * 确保字符串响应无乱码、设备图纸等资源可被前端访问。
 * </p>
 *
 * <p>
 * 静态资源映射通过 {@link StaticResourceProperties} 配置（默认 {@code /files/**} → {@code file:data/images/}），
 * 供前端渲染 references 事件中的 IMAGE 类型引用。
 * </p>
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final StaticResourceProperties staticResourceProperties;

    /**
     * 自定义消息转换器配置
     *
     * <p>
     * 这里通过往转换器列表的首位插入一个 UTF-8 的 {@link StringHttpMessageConverter}，
     * 来覆盖默认的 String 类型消息转换行为
     * </p>
     *
     * @param converters Spring MVC 默认注册的消息转换器列表
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 使用 UTF-8 作为字符串响应的默认编码
        StringHttpMessageConverter stringConverter = new StringHttpMessageConverter(StandardCharsets.UTF_8);

        // 避免在响应的 Content-Type 头中自动添加 "charset" 列表（accept-charset），
        // 防止某些客户端或中间件对该头部解析不兼容
        stringConverter.setWriteAcceptCharset(false);

        // 将自定义的 String 消息转换器放在列表首位，提高其匹配优先级
        converters.add(0, stringConverter);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (staticResourceProperties.isEnabled()) {
            registry.addResourceHandler(staticResourceProperties.getUrlPattern())
                    .addResourceLocations(staticResourceProperties.getLocation());
        }
    }
}

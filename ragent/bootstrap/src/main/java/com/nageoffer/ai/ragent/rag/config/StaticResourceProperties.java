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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 静态资源映射配置
 *
 * <p>
 * 用于将本地文件目录（如数据目录下的图片、图纸）暴露为 HTTP 可访问的静态资源，
 * 供前端渲染多模态引用（设备图纸缩略图等）。
 * </p>
 *
 * <p>
 * 默认值：
 * <ul>
 *   <li>{@code url-pattern}：{@code /files/**}，URL 前缀</li>
 *   <li>{@code location}：{@code file:data/images/}，相对 bootstrap 模块工作目录</li>
 * </ul>
 * 生产部署若工作目录变化，在 application.yaml 中覆盖为绝对路径即可。
 * </p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ragent.static-resource")
public class StaticResourceProperties {

    /**
     * 静态资源 URL 匹配模式（默认 {@code /files/**}）
     */
    private String urlPattern = "/files/**";

    /**
     * 静态资源本地文件目录（默认 {@code file:data/images/}）
     */
    private String location = "file:data/images/";

    /**
     * 是否启用静态资源映射
     */
    private boolean enabled = true;
}

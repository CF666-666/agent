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

package com.nageoffer.ai.ragent.user.controller.request;

import lombok.Data;

/**
 * 当前登录用户自助资料更新请求
 *
 * <p>
 * 所有字段均可选：传 {@code null} 表示不修改该字段；
 * {@code avatar} 传空字符串表示清除头像（前端回退默认头像）。
 * </p>
 */
@Data
public class ProfileUpdateRequest {

    /**
     * 用户名（可选，传 null 不修改）
     */
    private String username;

    /**
     * 头像地址（可选，传 null 不修改；传空串表示清除头像）
     */
    private String avatar;
}

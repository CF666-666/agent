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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.config.StaticResourceProperties;
import com.nageoffer.ai.ragent.user.controller.request.ChangePasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.ProfileUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserCreateRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserPageRequest;
import com.nageoffer.ai.ragent.user.controller.request.UserUpdateRequest;
import com.nageoffer.ai.ragent.user.controller.vo.UserVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.enums.UserRole;
import com.nageoffer.ai.ragent.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final String DEFAULT_ADMIN_USERNAME = "admin";

    /**
     * 头像文件大小上限：5MB
     */
    private static final long MAX_AVATAR_SIZE = 5L * 1024 * 1024;

    private final UserMapper userMapper;

    private final StaticResourceProperties staticResourceProperties;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Override
    public IPage<UserVO> pageQuery(UserPageRequest requestParam) {
        String keyword = StrUtil.trimToNull(requestParam.getKeyword());
        Page<UserDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<UserDO> result = userMapper.selectPage(
                page,
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getDeleted, 0)
                        .and(StrUtil.isNotBlank(keyword), wrapper -> wrapper
                                .like(UserDO::getUsername, keyword)
                                .or()
                                .like(UserDO::getRole, keyword))
                        .orderByDesc(UserDO::getUpdateTime)
        );
        return result.convert(this::toVO);
    }

    @Override
    public String create(UserCreateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String username = StrUtil.trimToNull(requestParam.getUsername());
        String password = StrUtil.trimToNull(requestParam.getPassword());
        String role = StrUtil.trimToNull(requestParam.getRole());
        Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
        Assert.notBlank(password, () -> new ClientException("密码不能为空"));

        if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
            throw new ClientException("默认管理员用户名不可用");
        }
        role = normalizeRole(role);
        ensureUsernameAvailable(username, null);

        UserDO record = UserDO.builder()
                .username(username)
                .password(password)
                .role(role)
                .avatar(StrUtil.trimToNull(requestParam.getAvatar()))
                .build();
        userMapper.insert(record);
        return String.valueOf(record.getId());
    }

    @Override
    public void update(String id, UserUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);

        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (!username.equals(record.getUsername())) {
                if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                    throw new ClientException("默认管理员用户名不可用");
                }
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        if (requestParam.getRole() != null) {
            record.setRole(normalizeRole(requestParam.getRole()));
        }

        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        if (requestParam.getPassword() != null) {
            String password = StrUtil.trimToNull(requestParam.getPassword());
            Assert.notBlank(password, () -> new ClientException("新密码不能为空"));
            record.setPassword(password);
        }

        userMapper.updateById(record);
    }

    @Override
    public void delete(String id) {
        UserDO record = loadById(id);
        ensureNotDefaultAdmin(record);
        userMapper.deleteById(record.getId());
    }

    @Override
    public void changePassword(ChangePasswordRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        String current = StrUtil.trimToNull(requestParam.getCurrentPassword());
        String next = StrUtil.trimToNull(requestParam.getNewPassword());
        Assert.notBlank(current, () -> new ClientException("当前密码不能为空"));
        Assert.notBlank(next, () -> new ClientException("新密码不能为空"));

        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        if (!passwordMatches(current, record.getPassword())) {
            throw new ClientException("当前密码不正确");
        }
        record.setPassword(next);
        userMapper.updateById(record);
    }

    @Override
    public void updateProfile(ProfileUpdateRequest requestParam) {
        Assert.notNull(requestParam, () -> new ClientException("请求不能为空"));
        LoginUser loginUser = UserContext.requireUser();
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, loginUser.getUserId())
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));

        // 用户名：仅非 null 时修改
        // 校验顺序：先判断当前记录是否为默认 admin（禁止改用户名），再判断目标是否为保留名，
        // 保证 admin 用户即使提交 username="admin" 也只提示"禁止改名"，不影响其保存头像。
        if (requestParam.getUsername() != null) {
            String username = StrUtil.trimToNull(requestParam.getUsername());
            Assert.notBlank(username, () -> new ClientException("用户名不能为空"));
            if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
                throw new ClientException("默认管理员不允许修改用户名");
            }
            if (DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(username)) {
                throw new ClientException("默认管理员用户名不可用");
            }
            if (!username.equals(record.getUsername())) {
                ensureUsernameAvailable(username, record.getId());
            }
            record.setUsername(username);
        }

        // 头像：非 null 即更新；空串表示清除头像（前端回退默认头像）
        if (requestParam.getAvatar() != null) {
            record.setAvatar(StrUtil.trimToNull(requestParam.getAvatar()));
        }

        userMapper.updateById(record);
    }

    @Override
    public String uploadAvatar(MultipartFile file) {
        Assert.notNull(file, () -> new ClientException("上传文件不能为空"));
        Assert.isTrue(!file.isEmpty(), () -> new ClientException("上传文件为空"));
        Assert.isTrue(file.getSize() > 0 && file.getSize() <= MAX_AVATAR_SIZE,
                () -> new ClientException("头像文件不能超过 5MB"));

        Path avatarDir = resolveAvatarDir();
        String rawName = UUID.randomUUID().toString().replace("-", "");
        Path target = null;
        try {
            Files.createDirectories(avatarDir);
            // 以文件头魔数判定真实类型，忽略用户文件名（防止伪装扩展名上传非图片内容）
            String imageType;
            try (BufferedInputStream bis = new BufferedInputStream(file.getInputStream())) {
                imageType = detectImageType(bis);
            }
            if (imageType == null) {
                throw new ClientException("仅支持 jpg/jpeg/png/gif/webp 格式的图片");
            }
            String filename = rawName + "." + imageType;
            target = avatarDir.resolve(filename);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
            log.info("头像上传成功: {} → {}", filename, target);
            return buildAvatarUrl(filename);
        } catch (IOException e) {
            if (target != null) {
                deleteQuietly(target);
            }
            log.error("头像写入磁盘失败: {}", rawName, e);
            throw new ClientException("头像保存失败，请稍后重试");
        }
    }

    /**
     * 通过文件头魔数识别图片类型（jpg/png/gif/webp），非白名单图片返回 {@code null}
     */
    private String detectImageType(InputStream in) throws IOException {
        byte[] header = in.readNBytes(12);
        // WebP: RIFF....WEBP
        if (header.length >= 12 && startsWith(header, new byte[]{0x52, 0x49, 0x46, 0x46})
                && header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
            return "webp";
        }
        if (startsWith(header, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return "jpg";
        }
        if (startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "png";
        }
        if (startsWith(header, new byte[]{0x47, 0x49, 0x46, 0x38})) {
            return "gif";
        }
        return null;
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析头像存储目录：静态资源根目录（file:data/images/ → data/images/）+ avatars 子目录
     */
    private Path resolveAvatarDir() {
        String location = staticResourceProperties.getLocation();
        // 去掉 file: 前缀（兼容 file:/abs/path 与 file:relative/path）
        String root = location.replaceFirst("^file://", "").replaceFirst("^file:", "");
        return Paths.get(root, "avatars");
    }

    /**
     * 构造头像可访问 URL：context-path + 静态资源 url-pattern + avatars 相对路径
     */
    private String buildAvatarUrl(String filename) {
        String urlPrefix = staticResourceProperties.getUrlPattern().replace("/**", "");
        return contextPath + urlPrefix + "/avatars/" + filename;
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignore) {
            // 清理失败不影响主流程，仅记录
            log.warn("清理失败的头像文件失败: {}", target);
        }
    }

    private UserDO loadById(String id) {
        UserDO record = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getId, id)
                        .eq(UserDO::getDeleted, 0)
        );
        Assert.notNull(record, () -> new ClientException("用户不存在"));
        return record;
    }

    private void ensureNotDefaultAdmin(UserDO record) {
        if (record != null && DEFAULT_ADMIN_USERNAME.equalsIgnoreCase(record.getUsername())) {
            throw new ClientException("默认管理员不允许修改或删除");
        }
    }

    private void ensureUsernameAvailable(String username, String excludeId) {
        UserDO existing = userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
                        .ne(excludeId != null, UserDO::getId, excludeId)
        );
        if (existing != null) {
            throw new ClientException("用户名已存在");
        }
    }

    private String normalizeRole(String role) {
        String value = StrUtil.trimToNull(role);
        if (StrUtil.isBlank(value)) {
            return UserRole.USER.getCode();
        }
        if (UserRole.ADMIN.getCode().equalsIgnoreCase(value)) {
            return UserRole.ADMIN.getCode();
        }
        if (UserRole.USER.getCode().equalsIgnoreCase(value)) {
            return UserRole.USER.getCode();
        }
        throw new ClientException("角色类型不合法");
    }

    private boolean passwordMatches(String input, String stored) {
        if (stored == null) {
            return input == null;
        }
        return stored.equals(input);
    }

    private UserVO toVO(UserDO record) {
        return UserVO.builder()
                .id(String.valueOf(record.getId()))
                .username(record.getUsername())
                .role(record.getRole())
                .avatar(record.getAvatar())
                .createTime(record.getCreateTime())
                .updateTime(record.getUpdateTime())
                .build();
    }
}

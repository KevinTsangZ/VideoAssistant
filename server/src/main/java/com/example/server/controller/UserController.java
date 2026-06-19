package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.entity.User;
import com.example.server.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
//加上这个是为了防止跨域问题漏网
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class UserController {

    private static final int FREE_UPLOAD_LIMIT = 5;
    private static final String DEFAULT_AI_MODEL = "deepseek-ai/DeepSeek-R1";

    @Autowired(required = false)
    private UserMapper userMapper;

    @Value("${ai.deepseek.base-url}")
    private String defaultAiBaseUrl;

    //注册接口
    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody User user) {
        Map<String, Object> result = new HashMap<>();
        try {
            //打印日志，确认数据进来了
            System.out.println("收到注册请求: " + user.getUsername());

            //检查 Mapper 是否注入成功
            if (userMapper == null) {
                throw new RuntimeException("UserMapper 未注入，请检查 @Mapper 注解！");
            }

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", user.getUsername());
            if (userMapper.selectCount(query) > 0) {
                result.put("code", 400);
                result.put("msg", "该账号已存在");
                return result;
            }

            //默认角色
            if (user.getNickname() == null || user.getNickname().isEmpty()) {
                user.setNickname("用户" + System.currentTimeMillis());
            }
            user.setRole("USER");
            user.setFreeUploadUsed(0);

            userMapper.insert(user); //关键动作

            result.put("code", 200);
            result.put("msg", "注册成功");
            result.put("data", sanitizeUser(user));
        } catch (Exception e) {
            //如果在黑窗口看到这个报错，就知道原因了
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "后端报错: " + e.getMessage());
        }
        return result;
    }

    //登录接口
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody User loginUser) {
        Map<String, Object> result = new HashMap<>();
        try {
            System.out.println("收到登录请求: " + loginUser.getUsername());

            QueryWrapper<User> query = new QueryWrapper<>();
            query.eq("username", loginUser.getUsername());
            query.eq("password", loginUser.getPassword());

            User dbUser = userMapper.selectOne(query);

            if (dbUser == null) {
                result.put("code", 401);
                result.put("msg", "账号或密码错误");
            } else {
                result.put("code", 200);
                result.put("msg", "登录成功");
                result.put("token", "user_" + dbUser.getId());
                result.put("userInfo", sanitizeUser(dbUser));
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "登录报错: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/quota")
    public Map<String, Object> quota(@RequestParam("userId") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            User dbUser = userMapper.selectById(userId);
            if (dbUser == null) {
                result.put("code", 404);
                result.put("msg", "用户不存在");
                return result;
            }

            int used = dbUser.getFreeUploadUsed() == null ? 0 : dbUser.getFreeUploadUsed();
            result.put("code", 200);
            result.put("freeUploadLimit", FREE_UPLOAD_LIMIT);
            result.put("freeUploadUsed", used);
            result.put("remainingFreeUploads", Math.max(0, FREE_UPLOAD_LIMIT - used));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "查询免费次数失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/ai-config")
    public Map<String, Object> getAiConfig(@RequestParam("userId") Long userId) {
        Map<String, Object> result = new HashMap<>();
        try {
            User dbUser = userMapper.selectById(userId);
            if (dbUser == null) {
                result.put("code", 404);
                result.put("msg", "用户不存在");
                return result;
            }
            result.put("code", 200);
            result.put("aiBaseUrl", firstNonBlank(dbUser.getAiBaseUrl(), defaultAiBaseUrl));
            result.put("aiModel", firstNonBlank(dbUser.getAiModel(), DEFAULT_AI_MODEL));
            result.put("hasAiApiKey", hasText(dbUser.getAiApiKey()));
            result.put("maskedAiApiKey", maskApiKey(dbUser.getAiApiKey()));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "读取模型设置失败: " + e.getMessage());
        }
        return result;
    }

    @PostMapping("/ai-config")
    public Map<String, Object> saveAiConfig(@RequestBody Map<String, Object> payload) {
        Map<String, Object> result = new HashMap<>();
        try {
            Long userId = Long.valueOf(String.valueOf(payload.get("userId")));
            User dbUser = userMapper.selectById(userId);
            if (dbUser == null) {
                result.put("code", 404);
                result.put("msg", "用户不存在");
                return result;
            }

            String aiBaseUrl = normalizeNullable(payload.get("aiBaseUrl"));
            String aiApiKey = normalizeNullable(payload.get("aiApiKey"));
            String aiModel = normalizeNullable(payload.get("aiModel"));
            boolean clearApiKey = Boolean.TRUE.equals(payload.get("clearApiKey"));

            UpdateWrapper<User> update = new UpdateWrapper<>();
            update.eq("id", userId);
            update.set("ai_base_url", firstNonBlank(aiBaseUrl, defaultAiBaseUrl));
            update.set("ai_model", firstNonBlank(aiModel, DEFAULT_AI_MODEL));
            if (clearApiKey) {
                update.set("ai_api_key", null);
            } else if (hasText(aiApiKey)) {
                update.set("ai_api_key", aiApiKey);
            }
            userMapper.update(null, update);

            User savedUser = userMapper.selectById(userId);
            boolean hasAiApiKey = hasText(savedUser.getAiApiKey());
            String maskedAiApiKey = maskApiKey(savedUser.getAiApiKey());
            result.put("code", 200);
            result.put("msg", "模型设置已保存");
            result.put("aiBaseUrl", firstNonBlank(savedUser.getAiBaseUrl(), defaultAiBaseUrl));
            result.put("aiModel", firstNonBlank(savedUser.getAiModel(), DEFAULT_AI_MODEL));
            result.put("hasAiApiKey", hasAiApiKey);
            result.put("maskedAiApiKey", maskedAiApiKey);
            result.put("userInfo", sanitizeUser(savedUser));
        } catch (Exception e) {
            e.printStackTrace();
            result.put("code", 500);
            result.put("msg", "保存模型设置失败: " + e.getMessage());
        }
        return result;
    }

    private User sanitizeUser(User user) {
        if (user == null) {
            return null;
        }
        user.setPassword(null);
        user.setHasAiApiKey(hasText(user.getAiApiKey()));
        user.setMaskedAiApiKey(maskApiKey(user.getAiApiKey()));
        user.setAiApiKey(null);
        return user;
    }

    private String normalizeNullable(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String maskApiKey(String apiKey) {
        if (!hasText(apiKey)) {
            return "";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
    }
}

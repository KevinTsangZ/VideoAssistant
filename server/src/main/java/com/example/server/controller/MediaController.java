package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    private static final int FREE_UPLOAD_LIMIT = 5;

    @Autowired(required = false)
    private MediaFileMapper mediaFileMapper;

    @Autowired(required = false)
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MinioUtils minioUtils;

    @Autowired
    private YtDlpUtils ytDlpUtils;

    @Autowired
    private MediaService mediaService;

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload() {
        String uploadId = mediaService.initChunkedUpload();
        return ResponseEntity.ok(uploadId);
    }


    @PostMapping("/upload")
    public ResponseEntity<String> upload(@RequestParam("file") MultipartFile file,
                                         @RequestParam(value = "userId", required = false) Long userId) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body("Upload failed: file is empty");
        }
        if (mediaFileMapper == null) {
            return ResponseEntity.status(500).body("Upload failed: database not ready");
        }
        UploadQuota quota = checkUploadQuota(userId);
        if (!quota.allowed()) {
            return ResponseEntity.status(403).body(quota.message());
        }
        try {
            System.out.println("Uploading to MinIO...");
            String fileUrl = minioUtils.uploadFile(file);
            System.out.println("MinIO upload success, url: " + fileUrl);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename(file.getOriginalFilename());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                if (quota.consumeFreeUpload()) {
                    incrementFreeUploadUsed(userId);
                }
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("Cache cleared: " + cacheKey);
            }

            return ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        }
    }

    @PostMapping("/upload-url")
    public org.springframework.http.ResponseEntity<String> uploadUrl(@RequestParam("url") String url,
                                                                     @RequestParam(value = "userId", required = false) Long userId) {
        File tempFile = null;
        try {
            if (url == null || url.isBlank()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Upload failed: url is empty");
            }
            if (mediaFileMapper == null) {
                return org.springframework.http.ResponseEntity.status(500).body("Upload failed: database not ready");
            }
            UploadQuota quota = checkUploadQuota(userId);
            if (!quota.allowed()) {
                return org.springframework.http.ResponseEntity.status(403).body(quota.message());
            }
            System.out.println("Received upload url: " + url);

            tempFile = ytDlpUtils.downloadVideo(url);

            String fileUrl = minioUtils.uploadLocalFile(tempFile);

            MediaFile mediaFile = new MediaFile();
            mediaFile.setFilename("WEB_" + tempFile.getName());
            mediaFile.setFilePath(fileUrl);
            mediaFile.setStatus("COMPLETED");
            mediaFile.setUploadTime(LocalDateTime.now());

            if (userId != null) {
                mediaFile.setUserId(userId);
            }

            mediaFileMapper.insert(mediaFile);

            if (userId != null) {
                if (quota.consumeFreeUpload()) {
                    incrementFreeUploadUsed(userId);
                }
                String cacheKey = "media:list:user:" + userId;
                redisTemplate.delete(cacheKey);
                System.out.println("Cache cleared: " + cacheKey);
            }

            return org.springframework.http.ResponseEntity.ok("Upload success");

        } catch (Exception e) {
            e.printStackTrace();
            return org.springframework.http.ResponseEntity.status(500).body("Upload failed: " + e.getMessage());
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    @GetMapping("/list")
    public List<MediaFile> getList(@RequestParam(value = "userId", required = false) Long userId) {
        String cacheKey = "media:list:user:" + (userId == null ? "anon" : userId);

        try {
            String json = redisTemplate.opsForValue().get(cacheKey);
            if (json != null) {
                System.out.println("命中 Redis 缓存，直接返回！");
                return objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
            }
        } catch (Exception e) {
            System.err.println("Redis 读取失败: " + e.getMessage());
        }

        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        if (userId != null) {
            query.eq("user_id", userId);
        } else {
            return List.of();
        }
        List<MediaFile> list = mediaFileMapper.selectList(query.orderByDesc("id"));

        try {
            String jsonToWrite = objectMapper.writeValueAsString(list);
            redisTemplate.opsForValue().set(cacheKey, jsonToWrite, 30, TimeUnit.MINUTES);
            System.out.println("已写入 Redis 缓存");
        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    //删除接口
    @DeleteMapping("/delete")
    public String delete(@RequestParam("id") Long id,
                         @RequestParam(value = "userId", required = false) Long userId) {

        MediaFile media = mediaFileMapper.selectById(id);
        if (media == null) return "文件不存在";

        if (userId != null && !media.getUserId().equals(userId)) {
            return "无权删除他人的文件";
        }

        if (media.getFilePath() != null && media.getFilePath().startsWith("http")) {
            minioUtils.removeFile(media.getFilePath());
        }

        mediaFileMapper.deleteById(id);

        if (media.getUserId() != null) {
            String cacheKey = "media:list:user:" + media.getUserId();
            redisTemplate.delete(cacheKey);
            System.out.println("缓存已清除: " + cacheKey);
        }

        return "删除成功";
    }

    private void incrementFreeUploadUsed(Long userId) {
        if (userMapper == null || userId == null) {
            return;
        }
        UpdateWrapper<com.example.server.entity.User> update = new UpdateWrapper<>();
        update.eq("id", userId);
        update.setSql("free_upload_used = free_upload_used + 1");
        userMapper.update(null, update);
    }

    private UploadQuota checkUploadQuota(Long userId) {
        if (userId == null) {
            return new UploadQuota(false, false, "请先登录后再上传视频");
        }
        if (userMapper == null) {
            return new UploadQuota(false, false, "Upload failed: user database not ready");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            return new UploadQuota(false, false, "用户不存在，请重新登录");
        }
        if (hasText(user.getAiApiKey())) {
            return new UploadQuota(true, false, "");
        }
        int used = user.getFreeUploadUsed() == null ? 0 : user.getFreeUploadUsed();
        if (used >= FREE_UPLOAD_LIMIT) {
            return new UploadQuota(false, false, "免费上传次数已用完，请点击右上角“API Key 配置”填写自己的 API Key 后继续上传");
        }
        return new UploadQuota(true, true, "");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private record UploadQuota(boolean allowed, boolean consumeFreeUpload, String message) {}
}

package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.entity.User;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.service.MediaService;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.YtDlpUtils; //确保导入这个
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/media")
@CrossOrigin(originPatterns = "*", allowCredentials = "true")
public class MediaController {

    private static final int FREE_UPLOAD_LIMIT = 5;
    private static final long CHUNK_SESSION_TTL_HOURS = 24;
    private static final String CHUNK_META_PREFIX = "upload:chunk:";
    private static final String CHUNK_INDEX_PREFIX = "upload:chunk:index:";

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

    @Autowired
    private RocketMQTemplate rocketMQTemplate;

    @PostMapping("/init-upload")
    public ResponseEntity<String> initUpload() {
        String uploadId = mediaService.initChunkedUpload();
        return ResponseEntity.ok(uploadId);
    }

    @PostMapping("/chunk/init")
    public ResponseEntity<Map<String, Object>> initChunkUpload(@RequestParam("userId") Long userId,
                                                               @RequestParam("filename") String filename,
                                                               @RequestParam("fileSize") Long fileSize,
                                                               @RequestParam("chunkSize") Long chunkSize,
                                                               @RequestParam("totalChunks") Integer totalChunks,
                                                               @RequestParam("fileMd5") String fileMd5) {
        if (!hasText(filename) || fileSize == null || fileSize <= 0 || chunkSize == null || chunkSize <= 0
                || totalChunks == null || totalChunks <= 0 || !hasText(fileMd5)) {
            return ResponseEntity.badRequest().body(response(400, "分片上传初始化参数不完整"));
        }
        UploadQuota quota = checkUploadQuota(userId);
        if (!quota.allowed()) {
            return ResponseEntity.status(403).body(response(403, quota.message()));
        }

        try {
            String indexKey = chunkIndexKey(userId, fileMd5);
            String uploadId = redisTemplate.opsForValue().get(indexKey);
            ChunkUploadMeta meta = hasText(uploadId) ? readChunkMeta(uploadId) : null;

            if (meta == null || "MERGED".equals(meta.status)) {
                uploadId = UUID.randomUUID().toString();
                meta = new ChunkUploadMeta();
                meta.uploadId = uploadId;
                meta.userId = userId;
                meta.filename = filename;
                meta.fileSize = fileSize;
                meta.chunkSize = chunkSize;
                meta.totalChunks = totalChunks;
                meta.fileMd5 = fileMd5.trim();
                meta.finalObjectName = minioUtils.generateObjectName(filename);
                meta.status = "UPLOADING";
                meta.createdAt = System.currentTimeMillis();
                writeChunkMeta(meta);
                redisTemplate.opsForValue().set(indexKey, uploadId, CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);
            }

            redisTemplate.expire(chunkMetaKey(uploadId), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.expire(chunkPartsKey(uploadId), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);

            Map<String, Object> body = response(200, "分片上传初始化成功");
            body.put("uploadId", uploadId);
            body.put("uploadedChunks", uploadedChunkIndexes(uploadId));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(response(500, "分片上传初始化失败: " + e.getMessage()));
        }
    }

    @PostMapping("/chunk/part")
    public ResponseEntity<Map<String, Object>> uploadChunkPart(@RequestParam("uploadId") String uploadId,
                                                               @RequestParam("chunkIndex") Integer chunkIndex,
                                                               @RequestParam("file") MultipartFile file) {
        if (!hasText(uploadId) || chunkIndex == null || file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(response(400, "分片上传参数不完整"));
        }

        try {
            ChunkUploadMeta meta = readChunkMeta(uploadId);
            if (meta == null) {
                return ResponseEntity.status(404).body(response(404, "上传会话不存在或已过期"));
            }
            if (chunkIndex < 0 || chunkIndex >= meta.totalChunks) {
                return ResponseEntity.badRequest().body(response(400, "分片序号越界"));
            }

            String part = String.valueOf(chunkIndex);
            if (!Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(chunkPartsKey(uploadId), part))) {
                minioUtils.uploadChunk(uploadId, chunkIndex, file);
                redisTemplate.opsForSet().add(chunkPartsKey(uploadId), part);
            }

            redisTemplate.expire(chunkMetaKey(uploadId), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);
            redisTemplate.expire(chunkPartsKey(uploadId), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);

            Map<String, Object> body = response(200, "分片上传成功");
            body.put("uploadedChunks", uploadedChunkIndexes(uploadId));
            body.put("uploadedCount", redisTemplate.opsForSet().size(chunkPartsKey(uploadId)));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(response(500, "分片上传失败: " + e.getMessage()));
        }
    }

    @PostMapping("/chunk/merge")
    public ResponseEntity<Map<String, Object>> mergeChunks(@RequestParam("uploadId") String uploadId) {
        if (!hasText(uploadId)) {
            return ResponseEntity.badRequest().body(response(400, "uploadId 不能为空"));
        }

        try {
            String result = redisTemplate.opsForValue().get(chunkResultKey(uploadId));
            if (hasText(result)) {
                Map<String, Object> body = response(200, "上传已合并完成");
                body.put("mediaId", Long.parseLong(result));
                return ResponseEntity.ok(body);
            }

            ChunkUploadMeta meta = readChunkMeta(uploadId);
            if (meta == null) {
                return ResponseEntity.status(404).body(response(404, "上传会话不存在或已过期"));
            }

            Long uploadedCount = redisTemplate.opsForSet().size(chunkPartsKey(uploadId));
            if (uploadedCount == null || uploadedCount < meta.totalChunks) {
                Map<String, Object> body = response(400, "分片尚未全部上传完成");
                body.put("uploadedCount", uploadedCount == null ? 0 : uploadedCount);
                body.put("totalChunks", meta.totalChunks);
                return ResponseEntity.badRequest().body(body);
            }

            UploadQuota quota = checkUploadQuota(meta.userId);
            if (!quota.allowed()) {
                return ResponseEntity.status(403).body(response(403, quota.message()));
            }

            String lockKey = chunkMergeLockKey(uploadId);
            Boolean locked = redisTemplate.opsForValue().setIfAbsent(lockKey, "1", 10, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(locked)) {
                return ResponseEntity.status(409).body(response(409, "正在合并，请稍后"));
            }

            try {
                result = redisTemplate.opsForValue().get(chunkResultKey(uploadId));
                if (hasText(result)) {
                    Map<String, Object> body = response(200, "上传已合并完成");
                    body.put("mediaId", Long.parseLong(result));
                    return ResponseEntity.ok(body);
                }

                String fileUrl = minioUtils.composeChunks(uploadId, meta.totalChunks, meta.finalObjectName);
                MediaFile mediaFile = createCompletedMedia(meta.filename, fileUrl, meta.userId, meta.fileMd5, quota);
                redisTemplate.opsForValue().set(chunkResultKey(uploadId), String.valueOf(mediaFile.getId()), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);
                minioUtils.removeChunkObjects(uploadId, meta.totalChunks);
                cleanupChunkSession(meta);

                Map<String, Object> body = response(200, "分片合并成功");
                body.put("mediaId", mediaFile.getId());
                return ResponseEntity.ok(body);
            } finally {
                redisTemplate.delete(lockKey);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(response(500, "分片合并失败: " + e.getMessage()));
        }
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

            createCompletedMedia(file.getOriginalFilename(), fileUrl, userId, null, quota);

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

            createCompletedMedia("WEB_" + tempFile.getName(), fileUrl, userId, null, quota);

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

    private MediaFile createCompletedMedia(String filename, String fileUrl, Long userId, String fileMd5, UploadQuota quota) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(filename);
        mediaFile.setFilePath(fileUrl);
        mediaFile.setFileMd5(fileMd5);
        mediaFile.setStatus("COMPLETED");
        mediaFile.setAiSummary("[MQ] 已进入消息队列，等待调度...");
        mediaFile.setUploadTime(LocalDateTime.now());

        if (userId != null) {
            mediaFile.setUserId(userId);
        }

        mediaFileMapper.insert(mediaFile);
        enqueueAnalysisTask(mediaFile.getId());

        if (userId != null) {
            if (quota != null && quota.consumeFreeUpload()) {
                incrementFreeUploadUsed(userId);
            }
            String cacheKey = "media:list:user:" + userId;
            redisTemplate.delete(cacheKey);
            System.out.println("Cache cleared: " + cacheKey);
        }

        return mediaFile;
    }

    private void enqueueAnalysisTask(Long mediaId) {
        if (mediaId == null) {
            return;
        }
        AnalysisTaskMsg msg = new AnalysisTaskMsg(mediaId, "START_ANALYSIS");
        rocketMQTemplate.convertAndSend("video-analysis-topic", msg);
        System.out.println("Auto analysis task queued, mediaId: " + mediaId);
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

    private Map<String, Object> response(int code, String msg) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", code);
        result.put("msg", msg);
        return result;
    }

    private void writeChunkMeta(ChunkUploadMeta meta) throws Exception {
        redisTemplate.opsForValue().set(chunkMetaKey(meta.uploadId), objectMapper.writeValueAsString(meta), CHUNK_SESSION_TTL_HOURS, TimeUnit.HOURS);
    }

    private ChunkUploadMeta readChunkMeta(String uploadId) throws Exception {
        String json = redisTemplate.opsForValue().get(chunkMetaKey(uploadId));
        if (!hasText(json)) {
            return null;
        }
        return objectMapper.readValue(json, ChunkUploadMeta.class);
    }

    private List<Integer> uploadedChunkIndexes(String uploadId) {
        Set<String> members = redisTemplate.opsForSet().members(chunkPartsKey(uploadId));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        return members.stream()
                .map(Integer::parseInt)
                .sorted()
                .toList();
    }

    private void cleanupChunkSession(ChunkUploadMeta meta) {
        redisTemplate.delete(chunkMetaKey(meta.uploadId));
        redisTemplate.delete(chunkPartsKey(meta.uploadId));
        redisTemplate.delete(chunkIndexKey(meta.userId, meta.fileMd5));
    }

    private String chunkMetaKey(String uploadId) {
        return CHUNK_META_PREFIX + uploadId + ":meta";
    }

    private String chunkPartsKey(String uploadId) {
        return CHUNK_META_PREFIX + uploadId + ":parts";
    }

    private String chunkResultKey(String uploadId) {
        return CHUNK_META_PREFIX + uploadId + ":result";
    }

    private String chunkMergeLockKey(String uploadId) {
        return CHUNK_META_PREFIX + uploadId + ":merge-lock";
    }

    private String chunkIndexKey(Long userId, String fileMd5) {
        return CHUNK_INDEX_PREFIX + userId + ":" + fileMd5;
    }

    private record UploadQuota(boolean allowed, boolean consumeFreeUpload, String message) {}

    private static class ChunkUploadMeta {
        public String uploadId;
        public Long userId;
        public String filename;
        public Long fileSize;
        public Long chunkSize;
        public Integer totalChunks;
        public String fileMd5;
        public String finalObjectName;
        public String status;
        public Long createdAt;
    }
}

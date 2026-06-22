package com.example.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.server.dto.AnalysisTaskMsg;
import com.example.server.entity.MediaFile;
import com.example.server.entity.User;
import com.example.server.entity.VideoAsset;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.UserMapper;
import com.example.server.mapper.VideoAssetMapper;
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

    @Autowired(required = false)
    private VideoAssetMapper videoAssetMapper;

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
                                                               @RequestParam("fileMd5") String fileMd5,
                                                               @RequestParam(value = "forceNew", defaultValue = "false") Boolean forceNew) {
        if (!hasText(filename) || fileSize == null || fileSize <= 0 || chunkSize == null || chunkSize <= 0
                || totalChunks == null || totalChunks <= 0 || !hasText(fileMd5)) {
            return ResponseEntity.badRequest().body(response(400, "分片上传初始化参数不完整"));
        }
        UploadQuota quota = checkUploadQuota(userId);
        if (!quota.allowed()) {
            return ResponseEntity.status(403).body(response(403, quota.message()));
        }

        try {
            if (!Boolean.TRUE.equals(forceNew)) {
                VideoAsset reusableAsset = findReusableAsset(fileMd5);
                if (reusableAsset != null) {
                    MediaFile mediaFile = createMediaFromAsset(reusableAsset, userId, filename, quota);
                    Map<String, Object> body = response(200, "已复用已有视频笔记");
                    body.put("reused", true);
                    body.put("mediaId", mediaFile.getId());
                    body.put("assetId", reusableAsset.getId());
                    body.put("uploadedChunks", List.of());
                    return ResponseEntity.ok(body);
                }
            }

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
                body.put("assetId", mediaFile.getAssetId());
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
                List<MediaFile> cachedList = objectMapper.readValue(json, new TypeReference<List<MediaFile>>(){});
                hydrateFromAssets(cachedList);
                return cachedList;
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
        hydrateFromAssets(list);

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

        if (userId != null && (media.getUserId() == null || !media.getUserId().equals(userId))) {
            return "无权删除他人的文件";
        }

        if (media.getAssetId() != null) {
            mediaFileMapper.deleteById(id);
            releaseAsset(media.getAssetId());
            clearUserCache(media.getUserId());
            return "删除成功";
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

    @PostMapping("/asset/detach-and-rerun")
    public ResponseEntity<Map<String, Object>> detachAndRerun(@RequestParam("mediaId") Long mediaId,
                                                              @RequestParam("userId") Long userId) {
        MediaFile media = mediaFileMapper.selectById(mediaId);
        if (media == null) {
            return ResponseEntity.status(404).body(response(404, "文件不存在"));
        }
        if (media.getUserId() == null || !media.getUserId().equals(userId)) {
            return ResponseEntity.status(403).body(response(403, "无权操作他人的视频笔记"));
        }

        try {
            if (media.getAssetId() != null && videoAssetMapper != null) {
                VideoAsset source = videoAssetMapper.selectById(media.getAssetId());
                if (source != null) {
                    Long oldAssetId = media.getAssetId();
                    VideoAsset privateAsset = createAsset(
                            firstNonBlank(source.getFilename(), media.getFilename()),
                            firstNonBlank(source.getFilePath(), media.getFilePath()),
                            firstNonBlank(source.getFileMd5(), media.getFileMd5()),
                            "PROCESSING"
                    );
                    media.setAssetId(privateAsset.getId());
                    media.setFilePath(privateAsset.getFilePath());
                    media.setFileMd5(privateAsset.getFileMd5());
                    incrementAssetRef(privateAsset.getId());
                    releaseAsset(oldAssetId);
                }
            }

            media.setTranscriptText(null);
            media.setAiSummary("[MQ] 已进入消息队列，等待调度...");
            mediaFileMapper.updateById(media);
            clearUserCache(media.getUserId());
            enqueueAnalysisTask(media.getId());

            Map<String, Object> body = response(200, "已开始独立重做视频笔记");
            body.put("mediaId", media.getId());
            body.put("assetId", media.getAssetId());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(response(500, "独立重做失败: " + e.getMessage()));
        }
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
        if (hasText(fileMd5) && videoAssetMapper != null) {
            VideoAsset asset = createAsset(filename, fileUrl, fileMd5, "PROCESSING");
            MediaFile mediaFile = createMediaFromAsset(asset, userId, filename, quota);
            enqueueAnalysisTask(mediaFile.getId());
            return mediaFile;
        }

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

    private VideoAsset createAsset(String filename, String fileUrl, String fileMd5, String status) {
        VideoAsset asset = new VideoAsset();
        asset.setFilename(filename);
        asset.setFilePath(fileUrl);
        asset.setFileMd5(fileMd5);
        asset.setStatus(status);
        asset.setAiSummary("[MQ] 已进入消息队列，等待调度...");
        asset.setRefCount(0);
        asset.setCreatedAt(LocalDateTime.now());
        asset.setUpdatedAt(LocalDateTime.now());
        videoAssetMapper.insert(asset);
        return asset;
    }

    private MediaFile createMediaFromAsset(VideoAsset asset, Long userId, String requestedFilename, UploadQuota quota) {
        MediaFile mediaFile = new MediaFile();
        mediaFile.setFilename(firstNonBlank(requestedFilename, asset.getFilename()));
        mediaFile.setFilePath(asset.getFilePath());
        mediaFile.setFileMd5(asset.getFileMd5());
        mediaFile.setAssetId(asset.getId());
        mediaFile.setStatus("COMPLETED");
        mediaFile.setTranscriptText(asset.getTranscriptText());
        mediaFile.setAiSummary(firstNonBlank(asset.getAiSummary(), "[MQ] 已进入消息队列，等待调度..."));
        mediaFile.setCoverUrl(asset.getCoverUrl());
        mediaFile.setUploadTime(LocalDateTime.now());

        if (userId != null) {
            mediaFile.setUserId(userId);
        }

        mediaFileMapper.insert(mediaFile);
        incrementAssetRef(asset.getId());

        if (userId != null) {
            if (quota != null && quota.consumeFreeUpload()) {
                incrementFreeUploadUsed(userId);
            }
            clearUserCache(userId);
        }

        return mediaFile;
    }

    private VideoAsset findReusableAsset(String fileMd5) {
        if (videoAssetMapper == null || !hasText(fileMd5)) {
            return null;
        }
        QueryWrapper<VideoAsset> completedQuery = new QueryWrapper<>();
        completedQuery.eq("file_md5", fileMd5.trim())
                .eq("status", "COMPLETED")
                .last("LIMIT 1");
        VideoAsset completed = videoAssetMapper.selectOne(completedQuery);
        if (completed != null) {
            return completed;
        }

        QueryWrapper<VideoAsset> processingQuery = new QueryWrapper<>();
        processingQuery.eq("file_md5", fileMd5.trim())
                .ne("status", "FAILED")
                .last("LIMIT 1");
        return videoAssetMapper.selectOne(processingQuery);
    }

    private void hydrateFromAssets(List<MediaFile> list) {
        if (videoAssetMapper == null || list == null || list.isEmpty()) {
            return;
        }
        for (MediaFile media : list) {
            if (media.getAssetId() == null) {
                continue;
            }
            VideoAsset asset = videoAssetMapper.selectById(media.getAssetId());
            if (asset == null) {
                continue;
            }
            media.setFilePath(firstNonBlank(asset.getFilePath(), media.getFilePath()));
            media.setFileMd5(firstNonBlank(asset.getFileMd5(), media.getFileMd5()));
            media.setTranscriptText(asset.getTranscriptText());
            media.setAiSummary(asset.getAiSummary());
            media.setCoverUrl(asset.getCoverUrl());
        }
    }

    private void incrementAssetRef(Long assetId) {
        if (videoAssetMapper == null || assetId == null) {
            return;
        }
        UpdateWrapper<VideoAsset> update = new UpdateWrapper<>();
        update.eq("id", assetId);
        update.setSql("ref_count = ref_count + 1");
        videoAssetMapper.update(null, update);
    }

    private void releaseAsset(Long assetId) {
        if (videoAssetMapper == null || assetId == null) {
            return;
        }
        VideoAsset asset = videoAssetMapper.selectById(assetId);
        if (asset == null) {
            return;
        }
        int refCount = asset.getRefCount() == null ? 0 : asset.getRefCount();
        if (refCount > 1) {
            UpdateWrapper<VideoAsset> update = new UpdateWrapper<>();
            update.eq("id", assetId);
            update.setSql("ref_count = GREATEST(ref_count - 1, 0)");
            videoAssetMapper.update(null, update);
            return;
        }

        if (hasText(asset.getFilePath())) {
            QueryWrapper<VideoAsset> samePathQuery = new QueryWrapper<>();
            samePathQuery.eq("file_path", asset.getFilePath());
            Long samePathCount = videoAssetMapper.selectCount(samePathQuery);
            if (samePathCount == null || samePathCount <= 1) {
                minioUtils.removeFile(asset.getFilePath());
            }
        }
        videoAssetMapper.deleteById(assetId);
    }

    private void clearUserCache(Long userId) {
        if (userId == null) {
            redisTemplate.delete("media:list:user:anon");
            return;
        }
        redisTemplate.delete("media:list:user:" + userId);
    }

    private String firstNonBlank(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
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

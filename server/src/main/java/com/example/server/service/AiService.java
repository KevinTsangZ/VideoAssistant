package com.example.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.server.entity.MediaFile;
import com.example.server.entity.VideoAsset;
import com.example.server.mapper.MediaFileMapper;
import com.example.server.mapper.VideoAssetMapper;
import com.example.server.strategy.AiAnalysisStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AiService {

    @Autowired
    private MediaFileMapper mediaFileMapper;

    @Autowired(required = false)
    private VideoAssetMapper videoAssetMapper;

    @Autowired
    @Qualifier("defaultAiStrategy")
    private AiAnalysisStrategy aiAnalysisStrategy;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public void asyncAnalyze(Long mediaId) {
        System.out.println("[AI] Start analysis, mediaId: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        if (mediaFile.getAssetId() != null && videoAssetMapper != null) {
            analyzeAsset(mediaFile);
            return;
        }

        try {
            String text = mediaFile.getTranscriptText();
            if (!hasUsefulTranscript(text)) {
                text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath(), mediaFile.getUserId());
                mediaFile.setTranscriptText(text);
            }

            String summary = aiAnalysisStrategy.generateSummaryFromText(text, mediaFile.getUserId());
            mediaFile.setAiSummary(summary);
            mediaFileMapper.updateById(mediaFile);
            clearUserCache(mediaFile.getUserId());

            System.out.println("[AI] Analysis completed, mediaId: " + mediaId);
        } catch (Exception e) {
            e.printStackTrace();
            mediaFile.setAiSummary("分析失败: " + e.getMessage());
            mediaFileMapper.updateById(mediaFile);
            clearUserCache(mediaFile.getUserId());
        }
    }

    @Async("aiTaskExecutor")
    public void asyncTranscribe(Long mediaId) {
        System.out.println("[AI] Start transcription, mediaId: " + mediaId);

        MediaFile mediaFile = mediaFileMapper.selectById(mediaId);
        if (mediaFile == null) return;

        if (mediaFile.getAssetId() != null && videoAssetMapper != null) {
            transcribeAsset(mediaFile);
            return;
        }

        try {
            String text = aiAnalysisStrategy.transcribe(mediaFile.getFilePath(), mediaFile.getUserId());
            mediaFile.setTranscriptText(text);
            mediaFileMapper.updateById(mediaFile);
            clearUserCache(mediaFile.getUserId());
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[AI] Transcription failed: " + e.getMessage());
        }
    }

    private void analyzeAsset(MediaFile mediaFile) {
        VideoAsset asset = videoAssetMapper.selectById(mediaFile.getAssetId());
        if (asset == null) return;

        try {
            asset.setStatus("PROCESSING");
            videoAssetMapper.updateById(asset);

            String text = asset.getTranscriptText();
            if (!hasUsefulTranscript(text)) {
                text = aiAnalysisStrategy.transcribe(asset.getFilePath(), mediaFile.getUserId());
                asset.setTranscriptText(text);
            }

            String summary = aiAnalysisStrategy.generateSummaryFromText(text, mediaFile.getUserId());
            asset.setAiSummary(summary);
            asset.setStatus("COMPLETED");
            videoAssetMapper.updateById(asset);

            mediaFile.setTranscriptText(text);
            mediaFile.setAiSummary(summary);
            mediaFileMapper.updateById(mediaFile);
            clearAssetUserCaches(asset.getId());
        } catch (Exception e) {
            e.printStackTrace();
            asset.setStatus("FAILED");
            asset.setAiSummary("分析失败: " + e.getMessage());
            videoAssetMapper.updateById(asset);
            clearAssetUserCaches(asset.getId());
        }
    }

    private void transcribeAsset(MediaFile mediaFile) {
        VideoAsset asset = videoAssetMapper.selectById(mediaFile.getAssetId());
        if (asset == null) return;

        try {
            String text = aiAnalysisStrategy.transcribe(asset.getFilePath(), mediaFile.getUserId());
            asset.setTranscriptText(text);
            asset.setStatus("PROCESSING");
            videoAssetMapper.updateById(asset);

            mediaFile.setTranscriptText(text);
            mediaFileMapper.updateById(mediaFile);
            clearAssetUserCaches(asset.getId());
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[AI] Asset transcription failed: " + e.getMessage());
        }
    }

    private void clearAssetUserCaches(Long assetId) {
        QueryWrapper<MediaFile> query = new QueryWrapper<>();
        query.eq("asset_id", assetId);
        List<MediaFile> mediaFiles = mediaFileMapper.selectList(query);
        for (MediaFile media : mediaFiles) {
            clearUserCache(media.getUserId());
        }
    }

    private void clearUserCache(Long userId) {
        String userIdStr = (userId == null) ? "anon" : String.valueOf(userId);
        redisTemplate.delete("media:list:user:" + userIdStr);
    }

    private boolean hasUsefulTranscript(String text) {
        return text != null
                && text.trim().length() > 10
                && !text.contains("失败")
                && !text.contains("错误")
                && !text.contains("Error");
    }
}

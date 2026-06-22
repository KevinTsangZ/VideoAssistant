package com.example.server.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("video_assets")
public class VideoAsset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String fileMd5;
    private String filename;
    private String filePath;
    private String status;
    private String transcriptText;
    private String aiSummary;
    private String coverUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer refCount;
}

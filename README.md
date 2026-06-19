# VideoAssistant

视频课代表-AI 是一个用于视频内容理解的全栈项目。用户可以上传本地视频或提交视频链接，系统会自动提取语音文字，并调用 AI 生成结构化视频笔记。

## 主要功能

- 用户登录与注册
- 本地视频上传和视频链接解析
- 自动语音识别，提取视频文字
- AI 总结，生成视频笔记
- 用户可配置自己的 OpenAI 兼容 API Key
- 免费上传次数限制与用量提示

## 技术栈

- 前端：Vue 3、Vite
- 后端：Spring Boot、MyBatis Plus
- 中间件：MySQL、Redis、MinIO、RocketMQ
- 媒体处理：FFmpeg、yt-dlp
- AI 接口：OpenAI 兼容接口，默认推荐硅基流动

## 本地运行

启动中间件：

```bash
docker-compose up -d
```

启动后端：

```bash
cd server
.\mvnw.cmd spring-boot:run
```

启动前端：

```bash
cd client
npm install
npm run dev
```

默认前端地址一般是：

```text
http://localhost:5173
```

## 配置说明

后端配置文件位于：

```text
server/src/main/resources/application.properties
```

本地运行前需要配置数据库、Redis、MinIO、RocketMQ、FFmpeg、yt-dlp 以及 AI API Key。请不要把真实 API Key 提交到 GitHub。

## 项目名称

- 中文名：视频课代表-AI
- 英文名：VideoAssistant

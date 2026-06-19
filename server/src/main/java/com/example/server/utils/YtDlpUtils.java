package com.example.server.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class YtDlpUtils {

    private static final String BILI_REFERER = "https://www.bilibili.com/";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final Pattern BVID_PATTERN = Pattern.compile("BV[0-9A-Za-z]{10}");

    @Value("${tool.ytdlp.path}")
    private String ytDlpPath;

    @Value("${tool.ffmpeg.dir}")
    private String ffmpegDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public File downloadVideo(String url) throws Exception {
        String tempDir = System.getProperty("java.io.tmpdir");
        String outputName = UUID.randomUUID() + ".mp4";
        String outputPath = tempDir + File.separator + outputName;

        System.out.println("[yt-dlp] Start download: " + url);

        List<String> command = new ArrayList<>();
        command.add(ytDlpPath);
        command.add("--user-agent");
        command.add(BROWSER_UA);
        command.add("--referer");
        command.add(BILI_REFERER);
        command.add("--add-header");
        command.add("Origin:https://www.bilibili.com");
        command.add("--recode-video");
        command.add("mp4");
        command.add("--ffmpeg-location");
        command.add(ffmpegDir);
        command.add("-o");
        command.add(outputPath);
        command.add("--no-check-certificate");
        command.add("--no-playlist");
        command.add(url);

        String logs = runCommand(command);
        File downloadedFile = new File(outputPath);
        if (!downloadedFile.exists()) {
            if (isBilibiliUrl(url) && logs.contains("HTTP Error 412")) {
                System.out.println("[bilibili-api] yt-dlp was blocked by 412; using API fallback.");
                return downloadBilibiliViaApi(url, outputPath);
            }
            throw new RuntimeException("Command failed: " + logs);
        }

        System.out.println("[yt-dlp] Download complete: " + (downloadedFile.length() / 1024) + "KB");
        return downloadedFile;
    }

    private String runCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder logs = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("ERROR") || line.contains("Downloading") || line.contains("[Merger]")) {
                    System.out.println("cmd > " + line);
                }
                logs.append(line).append('\n');
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            if (logs.toString().contains("HTTP Error 412")) {
                return logs.toString();
            }
            throw new RuntimeException("yt-dlp download failed: " + logs);
        }
        return logs.toString();
    }

    private File downloadBilibiliViaApi(String url, String outputPath) throws Exception {
        String bvid = resolveBvid(url);
        if (bvid == null) {
            throw new RuntimeException("Bilibili URL does not contain a valid BV id");
        }

        JsonNode view = getJson("https://api.bilibili.com/x/web-interface/view?bvid=" + bvid);
        if (view.path("code").asInt(-1) != 0) {
            throw new RuntimeException("Bilibili view API failed: " + view.path("message").asText());
        }

        JsonNode data = view.path("data");
        long cid = data.path("cid").asLong();
        String title = data.path("title").asText(bvid);
        System.out.println("[bilibili-api] Resolved: " + title + ", cid=" + cid);

        String playUrl = "https://api.bilibili.com/x/player/playurl?bvid=" + bvid
                + "&cid=" + cid + "&fnval=16&fourk=0";
        JsonNode play = getJson(playUrl);
        if (play.path("code").asInt(-1) != 0) {
            throw new RuntimeException("Bilibili playurl API failed: " + play.path("message").asText());
        }

        JsonNode dash = play.path("data").path("dash");
        JsonNode videoNode = bestStream(dash.path("video"));
        JsonNode audioNode = bestStream(dash.path("audio"));
        if (videoNode == null) {
            throw new RuntimeException("Bilibili API did not return a downloadable video stream");
        }

        File videoFile = File.createTempFile("bili_video_", ".m4s");
        File audioFile = audioNode == null ? null : File.createTempFile("bili_audio_", ".m4s");
        try {
            downloadToFile(streamUrl(videoNode), videoFile);
            if (audioNode != null) {
                downloadToFile(streamUrl(audioNode), audioFile);
            }
            mergeWithFfmpeg(videoFile, audioFile, new File(outputPath));
        } finally {
            videoFile.delete();
            if (audioFile != null) {
                audioFile.delete();
            }
        }

        File output = new File(outputPath);
        if (!output.exists()) {
            throw new RuntimeException("Bilibili fallback finished but output file was not generated");
        }
        System.out.println("[bilibili-api] Download complete: " + (output.length() / 1024) + "KB");
        return output;
    }

    private JsonNode getJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", BROWSER_UA)
                .header("Referer", BILI_REFERER)
                .header("Origin", "https://www.bilibili.com")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " while requesting " + url);
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode bestStream(JsonNode streams) {
        if (streams == null || !streams.isArray() || streams.isEmpty()) {
            return null;
        }
        return streams.findValuesAsText("id").isEmpty()
                ? streams.get(0)
                : iterable(streams).stream()
                .max(Comparator.comparingInt(node -> node.path("bandwidth").asInt(0)))
                .orElse(streams.get(0));
    }

    private List<JsonNode> iterable(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes;
    }

    private String streamUrl(JsonNode node) {
        String url = node.path("baseUrl").asText();
        if (url == null || url.isBlank()) {
            url = node.path("base_url").asText();
        }
        return url;
    }

    private void downloadToFile(String url, File target) throws Exception {
        if (url == null || url.isBlank()) {
            throw new RuntimeException("Bilibili stream URL is empty");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", BROWSER_UA)
                .header("Referer", BILI_REFERER)
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " while downloading Bilibili stream");
        }
        try (InputStream in = response.body()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void mergeWithFfmpeg(File videoFile, File audioFile, File outputFile) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(ffmpegPath());
        command.add("-y");
        command.add("-i");
        command.add(videoFile.getAbsolutePath());
        if (audioFile != null) {
            command.add("-i");
            command.add(audioFile.getAbsolutePath());
        }
        command.add("-c");
        command.add("copy");
        command.add(outputFile.getAbsolutePath());

        String logs = runCommand(command);
        if (!outputFile.exists()) {
            throw new RuntimeException("ffmpeg merge failed: " + logs);
        }
    }

    private String ffmpegPath() {
        if (ffmpegDir.endsWith("ffmpeg")) {
            return ffmpegDir;
        }
        return ffmpegDir + File.separator + "ffmpeg";
    }

    private boolean isBilibiliUrl(String url) {
        return url != null && (url.contains("bilibili.com") || url.contains("b23.tv"));
    }

    private String resolveBvid(String url) throws Exception {
        String bvid = extractBvid(url);
        if (bvid != null || url == null || !url.contains("b23.tv")) {
            return bvid;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", BROWSER_UA)
                .GET()
                .build();
        HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        return extractBvid(response.uri().toString());
    }

    private String extractBvid(String url) {
        Matcher matcher = BVID_PATTERN.matcher(url);
        return matcher.find() ? matcher.group() : null;
    }
}

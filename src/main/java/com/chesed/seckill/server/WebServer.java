package com.chesed.seckill.server;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 用 JDK 自带的 HttpServer 搭的极简 Web 服务。
 *
 * <p>为什么不用 Spring Boot：这个项目的重点是并发结构对比，引入框架会把
 * 注意力从"队列和锁的差异"转到"框架配置"上；而且 HttpServer 是 JDK 内置的，
 * 答辩时不用联网下依赖。代价是要自己写路由和 JSON 序列化。
 * </p>
 */
public final class WebServer {

    private final HttpServer server;
    private final ApiHandler api;

    public WebServer(int port, ApiHandler api) throws IOException {
        this.api = api;
        this.server = HttpServer.create(new InetSocketAddress(port), 128);
        this.server.setExecutor(Executors.newFixedThreadPool(
                Math.max(8, Runtime.getRuntime().availableProcessors() * 2)));
        this.server.createContext("/api/", this::handleApi);
        this.server.createContext("/", this::handleStatic);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleApi(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        String path = ex.getRequestURI().getPath();
        Map<String, String> query = parseQuery(ex.getRequestURI().getRawQuery());
        String body = "";
        try {
            if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            }
            String json = api.dispatch(path, ex.getRequestMethod(), query, body);
            write(ex, 200, json);
        } catch (ApiException e) {
            write(ex, e.status(), "{\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (RuntimeException e) {
            write(ex, 500, "{\"error\":\"" + escape(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private void handleStatic(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        Path file = Assets.resolve(path);
        if (file == null) {
            // 顺手支持一下跑出来的实验 CSV，方便面板直接画
            Path csv = Assets.resolve("../../docs/experiments" + path);
            if (csv != null) {
                file = csv;
            }
        }
        if (file == null) {
            write(ex, 404, "not found");
            return;
        }
        String ct = contentType(file.getFileName().toString());
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        byte[] bytes = Files.readAllBytes(file);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (n.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (n.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (n.endsWith(".csv")) {
            return "text/csv; charset=utf-8";
        }
        if (n.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (n.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (n.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private static void write(com.sun.net.httpserver.HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                map.put(decode(pair), "");
            } else {
                map.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** 路由用的小异常，带 HTTP 状态码。 */
    public static final class ApiException extends RuntimeException {
        private final int status;

        public ApiException(int status, String msg) {
            super(msg);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 静态资源定位。开发时读 src/main/resources/web，打包后读 classpath 同目录。 */
    static final class Assets {

        private static final Path DEV_DIR = Path.of("src", "main", "resources", "web");

        static Path resolve(String urlPath) {
            String clean = urlPath.replace('\\', '/');
            while (clean.startsWith("/")) {
                clean = clean.substring(1);
            }
            if (clean.contains("..")) {
                return null;
            }
            Path p = DEV_DIR.resolve(clean).normalize();
            if (Files.isRegularFile(p)) {
                return p;
            }
            return null;
        }

        static List<String> list() {
            if (!Files.isDirectory(DEV_DIR)) {
                return List.of();
            }
            try (var s = Files.list(DEV_DIR)) {
                return s.filter(Files::isRegularFile).map(x -> x.getFileName().toString()).toList();
            } catch (IOException e) {
                return List.of();
            }
        }
    }
}

package com.chesed.seckill.server;

import java.nio.file.Files;
import java.nio.file.Path;

/** 启动入口。端口可用参数指定，默认 8080。数据放 ./data。 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("端口参数不合法: " + args[0]);
                System.exit(2);
            }
        }
        Path dataDir = Path.of("data");
        Files.createDirectories(dataDir);
        ApiHandler api = new ApiHandler(dataDir);
        WebServer server = new WebServer(port, api);
        server.start();
        System.out.println("========================================");
        System.out.println("  秒杀订单队列优化平台已启动");
        System.out.println("  请访问: http://127.0.0.1:" + server.port());
        System.out.println("  数据目录: " + dataDir.toAbsolutePath());
        System.out.println("========================================");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            api.shutdown();
        }));
    }
}

package com.yiyiooo.audiostream.source;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 手机当来源端时给 PC 端配置页看的两个接口。
 *
 * PC 端在配置页里选「对方设备」时打的就是 {@code GET http://<ip>:12570/api/devices}，
 * 所以这两个路由的返回体形状必须和 PC 端自己的 {@code HttpResult<T>} 一致，
 * 否则它解析不出来，表现是「填了 IP 却一台设备都没有」。
 *
 * 自己写 HTTP 而不用框架：只需要 GET + 两个固定路由。工程里其它部分也坚持只用平台 API，
 * 多引一层反而多一个版本坑。
 *
 * 只读请求行与请求头，不读请求体——这两个路由都是 GET，没有体。
 */
public final class SourceHttpServer implements Closeable {

    /** 谁来回答这两个路由。服务器本身不碰音频，设备清单由上层给。 */
    public interface Content {

        /** {@code /api/info} 的完整返回体。 */
        String infoJson();

        /** {@code /api/devices} 的完整返回体。 */
        String devicesJson();
    }

    /** 请求行与请求头的长度上限：这是不可信输入，不拦就有无限增长的内存。 */
    private static final int LINE_LIMIT = 8192;

    /** 一条连接从建立到读完请求头的时限，防半开连接一直占着工作线程。 */
    private static final int SOCKET_TIMEOUT_MS = 5000;

    private static final int WORKERS = 4;

    private final int requestedPort;
    private final Content content;

    private ServerSocket server;
    private ExecutorService workers;
    private volatile boolean running;
    private volatile int boundPort;

    public SourceHttpServer(int port, Content content) {
        this.requestedPort = port;
        this.content = content;
    }

    /** 实际监听的端口。传 0 进来时由系统分配，单元测试靠它拿到真端口。 */
    public int boundPort() {
        return boundPort;
    }

    /**
     * 绑定并开始接受连接，返回实际端口。
     *
     * 绑定失败直接把异常抛给上层：12570 被别的程序占了是用户要看到的事实，
     * 不是这里咽下去就完了的事。
     */
    public int start() throws IOException {
        if (running) return boundPort;
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(requestedPort));
        server = socket;
        boundPort = socket.getLocalPort();
        running = true;
        workers = Executors.newFixedThreadPool(WORKERS, daemonFactory("source-http"));
        Thread acceptor = new Thread(this::acceptLoop, "source-http-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        return boundPort;
    }

    @Override
    public void close() {
        running = false;
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 关监听失败没什么可做的
            }
        }
        ExecutorService pool = workers;
        workers = null;
        if (pool != null) pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            ServerSocket socket = server;
            if (socket == null) break;
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                ExecutorService pool = workers;
                if (pool == null) {
                    client.close();
                    break;
                }
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                // 关监听会让 accept 立刻抛出来，这是正常退出路径；其它情况单条连接不该带走监听
                if (!running) break;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(SOCKET_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in);
            if (requestLine == null) return;
            // 请求头要读干净再回：不读完客户端还在写，很容易被内核回一个重置
            while (true) {
                String line = readLine(in);
                if (line == null || line.isEmpty()) break;
            }
            respond(client, requestLine);
        } catch (IOException ignored) {
            // 对端半路断开是常态（浏览器预连接、探活），不值得报错
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // 同上
            }
        }
    }

    private void respond(Socket client, String requestLine) throws IOException {
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            write(client, 400, "Bad Request", SourceJson.error(400, "请求行看不懂"));
            return;
        }
        if (!"GET".equalsIgnoreCase(parts[0])) {
            write(client, 405, "Method Not Allowed", SourceJson.error(405, "这里只支持 GET"));
            return;
        }

        String path = parts[1];
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        // 尾斜杠容错：/api/devices/ 与 /api/devices 当同一个路由
        while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);

        if ("/api/info".equals(path)) {
            write(client, 200, "OK", content.infoJson());
        } else if ("/api/devices".equals(path)) {
            write(client, 200, "OK", content.devicesJson());
        } else {
            write(client, 404, "Not Found", SourceJson.error(404, "没有这个路由：" + path));
        }
    }

    private static void write(Socket client, int code, String reason, String body)
            throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        OutputStream out = client.getOutputStream();
        out.write(head.getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.flush();
    }

    /** 读一行，到 \n 为止；\r 丢掉。读不到任何字节才算结束（返回 null）。 */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        boolean sawAny = false;
        int b;
        while ((b = in.read()) >= 0) {
            sawAny = true;
            if (b == '\n') break;
            if (b != '\r' && buffer.size() < LINE_LIMIT) buffer.write(b);
        }
        if (!sawAny) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }
}

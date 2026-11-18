package toolcallfaults.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class RawClient implements AutoCloseable {
    final Socket socket;
    RawClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(4000);
    }
    void send(String method, String path, Map<String,String> headers, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n");
        headers.forEach((k,v) -> head.append(k).append(": ").append(v).append("\r\n"));
        head.append("Content-Length: ").append(bytes.length).append("\r\n\r\n");
        socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }
    byte[] all() throws IOException { return socket.getInputStream().readAllBytes(); }
    byte[] through(String ending) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (!out.toString(StandardCharsets.UTF_8).endsWith(ending)) {
            int b = socket.getInputStream().read();
            if (b < 0) throw new IOException("Premature EOF");
            out.write(b);
        }
        return out.toByteArray();
    }
    static String request(int port, String method, String path, Map<String,String> headers, String body) throws IOException {
        try (RawClient c = new RawClient(port)) { c.send(method,path,headers,body); return new String(c.all(),StandardCharsets.UTF_8); }
    }
    @Override public void close() throws IOException { socket.close(); }
}

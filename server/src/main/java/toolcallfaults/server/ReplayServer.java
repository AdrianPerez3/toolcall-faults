package toolcallfaults.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Byte replayer: routing and HTTP framing only, with no provider protocol knowledge. */
public final class ReplayServer implements AutoCloseable {
    private final ServerSocket listener;
    private final Map<String,Cell> cells = new ConcurrentHashMap<>();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcf-replay"); t.setDaemon(true); return t;
    });
    private static final class Cell {
        final Script script;
        final List<RequestRecord> log = new ArrayList<>();
        int next;
        boolean flagged;
        Cell(Script script) { this.script = script; }
    }
    private ReplayServer(int port) throws IOException {
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
        workers.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket socket = listener.accept(); sockets.add(socket);
                    workers.submit(() -> serve(socket));
                } catch (IOException | RejectedExecutionException e) { if (!listener.isClosed()) close(); }
            }
        });
    }
    public static ReplayServer start(int port) throws IOException { return new ReplayServer(port); }
    public int port() { return listener.getLocalPort(); }
    public void register(String cellId, Script script) {
        if (!cellId.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid cell id");
        if (cells.putIfAbsent(cellId, new Cell(script)) != null) throw new IllegalArgumentException("Duplicate cell id");
    }
    public List<RequestRecord> log(String cellId) {
        Cell cell = cells.get(cellId);
        if (cell == null) return List.of();
        synchronized (cell) { return List.copyOf(cell.log); }
    }
    public boolean flagged(String cellId) {
        Cell cell = cells.get(cellId);
        if (cell == null) return false;
        synchronized (cell) { return cell.flagged; }
    }
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int b; (b = in.read()) >= 0;) {
            if (b == '\n') return bytes.toString(StandardCharsets.ISO_8859_1).replaceFirst("\\r$", "");
            if (bytes.size() >= 65536) throw new IOException("HTTP line too long");
            bytes.write(b);
        }
        throw new EOFException();
    }
    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }
    private static boolean matches(String pattern, String path) {
        String regex = Arrays.stream(pattern.split("\\*", -1)).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining(".*"));
        return path.matches(regex);
    }
    private static RequestRecord update(RequestRecord r, long finished, boolean served) {
        return new RequestRecord(r.cellId(), r.seq(), r.method(), r.path(), r.exchangeIndex(),
                r.userAgent(), r.hasCredentialHeader(), r.retryHeaders(), r.body(), r.receivedAtMs(), finished, served);
    }
    private void serve(Socket socket) {
        Cell cell = null;
        int logIndex = -1;
        boolean served = false;
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            String[] start = line(in).split(" ", 3);
            if (start.length != 3 || !start[2].equals("HTTP/1.1")) throw new IOException("Expected HTTP/1.1");
            long received = System.currentTimeMillis();
            Map<String,String> headers = new HashMap<>();
            for (String h; !(h = line(in)).isEmpty();) {
                int colon = h.indexOf(':');
                if (colon < 1) throw new IOException("Invalid HTTP header");
                headers.put(h.substring(0,colon).toLowerCase(Locale.ROOT), h.substring(colon+1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            if (length < 0) throw new IOException("Invalid content length");
            byte[] bodyPrefix = in.readNBytes(Math.min(length,65536));
            if (bodyPrefix.length != Math.min(length,65536)) throw new EOFException();
            long left = length - bodyPrefix.length;
            while (left > 0) { long n = in.skip(left); if (n == 0) { if (in.read() < 0) throw new EOFException(); n = 1; } left -= n; }
            String body = new String(bodyPrefix, StandardCharsets.UTF_8);
            if (body.length() > 16384) body = body.substring(0,16384);
            String[] route = start[1].split("/", 4);
            String id = route.length == 4 && route[1].equals("c") ? route[2] : "";
            String path = route.length == 4 ? "/" + route[3] : start[1];
            String matchPath = path.split("\\?",2)[0];
            cell = cells.get(id);
            Exchange exchange = null;
            int status = 404;
            if (cell != null) synchronized (cell) {
                int seq = cell.log.size() + 1;
                int index = -1;
                if (seq > 8) { status = 409; cell.flagged = true; }
                else {
                    int candidate = Math.min(cell.next,cell.script.exchanges().size()-1);
                    Exchange e = cell.script.exchanges().get(candidate);
                    if (e.method().equals(start[0]) && matches(e.pathPattern(), matchPath)) {
                        index = candidate; exchange = e; status = e.status(); cell.next++;
                    }
                }
                Map<String,String> retries = headers.containsKey("x-stainless-retry-count")
                        ? Map.of("x-stainless-retry-count",headers.get("x-stainless-retry-count")) : Map.of();
                logIndex = cell.log.size();
                cell.log.add(new RequestRecord(id,seq,start[0],path,index,headers.get("user-agent"),
                        headers.containsKey("authorization") || headers.containsKey("x-api-key"),
                        retries,body,received,0,false));
            }
            if (exchange == null) {
                String message = status == 409 ? "request limit exceeded" : "no exchange for " + start[0] + " " + path;
                exchange = new Exchange(start[0],path,status,Map.of(),BodyKind.WHOLE,
                        List.of(ascii(message)),End.CLEAN,0,null);
            }
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder("HTTP/1.1 " + status + (status == 200 ? " OK\r\n" : " Error\r\n"));
            exchange.headers().forEach((k,v) -> head.append(k).append(": ").append(v).append("\r\n"));
            if (exchange.kind() == BodyKind.STREAMED) head.append("Transfer-Encoding: chunked\r\n");
            else head.append("Content-Length: ").append(exchange.parts().stream().mapToLong(p -> p.length).sum()).append("\r\n");
            out.write(ascii(head.append("Connection: close\r\n\r\n").toString()));
            if (exchange.kind() == BodyKind.STREAMED) {
                for (int i = 0; i < exchange.parts().size(); i++) {
                    Thread.sleep(exchange.delaysMs() == null ? 15 : exchange.delaysMs()[i]);
                    byte[] part = exchange.parts().get(i);
                    ByteArrayOutputStream chunk = new ByteArrayOutputStream();
                    chunk.write(ascii(Integer.toHexString(part.length) + "\r\n")); chunk.write(part); chunk.write(ascii("\r\n"));
                    out.write(chunk.toByteArray()); out.flush();
                }
            } else {
                ByteArrayOutputStream whole = new ByteArrayOutputStream();
                for (byte[] part : exchange.parts()) whole.write(part);
                out.write(whole.toByteArray()); out.flush();
            }
            if (exchange.end() == End.CLEAN && exchange.kind() == BodyKind.STREAMED) out.write(ascii("0\r\n\r\n"));
            // A zero-linger close discards bytes not yet transmitted or acknowledged; wait as before a part.
            if (exchange.end() == End.RESET) { Thread.sleep(15); socket.setSoLinger(true,0); }
            if (cell != null) synchronized (cell) {
                served = cell.log.get(logIndex).exchangeIndex() >= 0;
                cell.log.set(logIndex,update(cell.log.get(logIndex),0,served));
            }
            if (exchange.end() == End.STALL) Thread.sleep(exchange.stallMs());
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (cell != null && logIndex >= 0) synchronized (cell) {
                cell.log.set(logIndex,update(cell.log.get(logIndex),System.currentTimeMillis(),served));
            }
            try { socket.close(); } catch (IOException ignored) { }
            sockets.remove(socket);
        }
    }
    @Override public void close() {
        try { listener.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
        workers.shutdownNow();
    }
}

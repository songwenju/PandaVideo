package dev.pandavideo.tv.cast;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Activity-owned HTTP and SSDP endpoints; sockets and worker queues have bounded lifetimes. */
final class ReceiverServer implements AutoCloseable {
    private final String uuid, name;
    private final Transport.Player player;
    private final ServerSocket http;
    private final MulticastSocket ssdp;
    private final String location;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(16));
    private volatile boolean closed;
    private final InetAddress group = InetAddress.getByName("239.255.255.250");
    private final NetworkInterface network;

    ReceiverServer(Inet4Address address, String uuid, String name, Transport.Player player) throws IOException {
        this.uuid = uuid; this.name = name; this.player = player;
        network = NetworkInterface.getByInetAddress(address);
        // Stable port supports IP-based discovery; reuse permits recovery after network changes.
        http = new ServerSocket();
        MulticastSocket multicast = null;
        try {
            http.setReuseAddress(true);
            http.bind(new InetSocketAddress(8899), 16);
            location = "http://" + address.getHostAddress() + ":8899/description.xml";
            multicast = new MulticastSocket(null);
            multicast.setReuseAddress(true);
            multicast.bind(new InetSocketAddress(1900));
            multicast.setNetworkInterface(network);
            multicast.setTimeToLive(2);
            multicast.joinGroup(new InetSocketAddress(group, 1900), network);
            ssdp = multicast;
        } catch (IOException e) {
            http.close();
            if (multicast != null) multicast.close();
            throw e;
        }
        new Thread(this::accept, "cast-http").start();
        new Thread(this::discover, "cast-ssdp").start();
    }

    private void accept() {
        while (!closed) {
            try {
                Socket socket = http.accept();
                socket.setSoTimeout(4000);
                clients.add(socket);
                try { workers.execute(() -> serve(socket)); }
                catch (RejectedExecutionException e) { clients.remove(socket); socket.close(); }
            } catch (IOException e) { if (!closed) close(); }
        }
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int b;
        while ((b = input.read()) != -1) {
            if (b == '\n') return bytes.toString(StandardCharsets.US_ASCII.name()).trim();
            bytes.write(b);
            if (bytes.size() > 4096) throw new IOException("Header too large");
        }
        throw new EOFException();
    }

    private void serve(Socket socket) {
        try (socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String[] request = line(input).split(" ");
            if (request.length != 3) { reply(socket, 400, ""); return; }
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; ; i++) {
                String row = line(input);
                if (row.isEmpty()) break;
                if (i >= 32 || !row.contains(":")) { reply(socket, 400, ""); return; }
                String key = row.substring(0, row.indexOf(':')).toLowerCase(Locale.ROOT);
                if (headers.put(key, row.substring(row.indexOf(':') + 1).trim()) != null) {
                    reply(socket, 400, ""); return;
                }
            }
            if (request[0].equals("GET") && request[1].equals("/description.xml")) {
                reply(socket, 200, Transport.descriptor(uuid, name));
            } else if (request[0].equals("GET") && request[1].equals("/avtransport.xml")) {
                reply(socket, 200, ServiceDescription.xml());
            } else if (request[0].equals("POST") && request[1].equals("/control")) {
                int length;
                try { length = Integer.parseInt(headers.getOrDefault("content-length", "-1")); }
                catch (NumberFormatException e) { length = -1; }
                if (length < 0 || length > Transport.MAX_BODY || headers.containsKey("transfer-encoding")) {
                    reply(socket, 413, ""); return;
                }
                // InputStream.readNBytes is unavailable on older Xiaomi TV firmware.
                byte[] bytes = new byte[length];
                int offset = 0;
                while (offset < length) {
                    int count = input.read(bytes, offset, length - offset);
                    if (count < 0) { reply(socket, 400, ""); return; }
                    offset += count;
                }
                Transport.Result result = Transport.execute(headers.getOrDefault("soapaction", ""),
                    new String(bytes, StandardCharsets.UTF_8), player);
                reply(socket, result.status(), result.body());
            } else reply(socket, 404, "");
        } catch (Exception ignored) { /* A malformed or disconnected peer cannot terminate the listener. */ }
        finally { clients.remove(socket); }
    }

    private static void reply(Socket socket, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("HTTP/1.1 " + status + (status == 200 ? " OK" : " Error")
            + "\r\nContent-Type: text/xml; charset=utf-8\r\nContent-Length: " + bytes.length
            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes); output.flush();
    }

    private void discover() {
        byte[] bytes = new byte[4096];
        List<String> targets = List.of("upnp:rootdevice", uuid, Transport.RENDERER, Transport.TYPE);
        long nextAnnounce = 0;
        try { ssdp.setSoTimeout(1000); } catch (SocketException e) { return; }
        while (!closed) {
            try {
                if (System.currentTimeMillis() >= nextAnnounce) {
                    announce("ssdp:alive", targets);
                    nextAnnounce = System.currentTimeMillis() + 60000;
                }
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                ssdp.receive(packet);
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.US_ASCII);
                if (!message.startsWith("M-SEARCH * HTTP/1.1\r\n")) continue;
                Map<String, String> fields = new HashMap<>();
                for (String row : message.split("\r\n")) {
                    int colon = row.indexOf(':');
                    if (colon > 0) fields.put(row.substring(0, colon).trim().toLowerCase(Locale.ROOT), row.substring(colon + 1).trim());
                }
                if (!"\"ssdp:discover\"".equalsIgnoreCase(fields.get("man"))) continue;
                String target = fields.get("st");
                for (String type : targets) {
                    if ("ssdp:all".equals(target) || type.equals(target)) {
                        String response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nEXT:\r\nLOCATION: "
                            + location + "\r\nSERVER: Android UPnP/1.0 PandaCast/0.1\r\nST: " + type
                            + "\r\nUSN: " + usn(type) + "\r\n\r\n";
                        byte[] reply = response.getBytes(StandardCharsets.US_ASCII);
                        ssdp.send(new DatagramPacket(reply, reply.length, packet.getAddress(), packet.getPort()));
                    }
                }
            } catch (SocketTimeoutException ignored) { /* Periodic alive advertisement. */ }
            catch (IOException e) {
                // Discovery is optional for Bonjour clients. A transient multicast failure
                // must not close the HTTP endpoint that DNS-SD still advertises.
                if (closed || ssdp.isClosed()) return;
                try { Thread.sleep(1_000); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Allows the owner to recover a closed transport even if the LAN address is unchanged. */
    boolean isRunning() {
        return !closed && !http.isClosed() && !ssdp.isClosed();
    }

    private String usn(String target) { return uuid.equals(target) ? uuid : uuid + "::" + target; }

    private void announce(String state, List<String> targets) throws IOException {
        for (String target : targets) {
            byte[] bytes = ("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: " + target
                + "\r\nNTS: " + state + "\r\nUSN: " + usn(target)
                + "\r\nLOCATION: " + location + "\r\nCACHE-CONTROL: max-age=120\r\n"
                + "SERVER: Android UPnP/1.0 PandaCast/0.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            ssdp.send(new DatagramPacket(bytes, bytes.length, group, 1900));
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { announce("ssdp:byebye", List.of("upnp:rootdevice", uuid, Transport.RENDERER, Transport.TYPE)); }
        catch (IOException ignored) {}
        ssdp.close();
        try { http.close(); } catch (IOException ignored) {}
        for (Socket client : clients) try { client.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}

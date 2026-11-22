package dev.pandavideo.tv.cast;

import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReceiverServerTest {
    @Test public void discoverySocketFailureDoesNotCloseHttpDescriptor() throws Exception {
        try (ReceiverServer server = new ReceiverServer(
            (Inet4Address) InetAddress.getByName("127.0.0.1"),
            "uuid:discovery-failure-test", "熊猫投屏 · test", new TransportTest.FakePlayer())) {
            assertDescriptorAvailable();
            Field field = ReceiverServer.class.getDeclaredField("ssdp");
            field.setAccessible(true);
            Thread discovery = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().equals("cast-ssdp") && thread.isAlive())
                .findFirst().orElseThrow();
            // A failed discovery socket must not remove the endpoint advertised by Bonjour.
            ((MulticastSocket) field.get(server)).close();
            discovery.join(2_000);
            assertFalse("A closed discovery socket must not spin", discovery.isAlive());
            assertDescriptorAvailable();
            assertFalse("The owner must be able to detect failed discovery", server.isRunning());
        }
    }

    @Test public void explicitShutdownClosesHttpAndReportsStopped() throws Exception {
        ReceiverServer server = new ReceiverServer(
            (Inet4Address) InetAddress.getByName("127.0.0.1"),
            "uuid:shutdown-test", "熊猫投屏 · test", new TransportTest.FakePlayer());
        try {
            assertTrue(server.isRunning());
            assertDescriptorAvailable();
        } finally {
            server.close();
        }
        assertFalse(server.isRunning());
        try {
            assertDescriptorAvailable();
            fail("Explicit shutdown must release the HTTP port");
        } catch (java.net.ConnectException expected) {
            // Teardown still closes transport; only discovery faults are isolated.
        }
    }

    private static void assertDescriptorAvailable() throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
            new URL("http://127.0.0.1:8899/description.xml").openConnection();
        connection.setConnectTimeout(1_000);
        connection.setReadTimeout(1_000);
        try {
            assertEquals(200, connection.getResponseCode());
            String body;
            try (var input = connection.getInputStream()) {
                body = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertTrue(body.contains("熊猫投屏 · test"));
        } finally {
            connection.disconnect();
        }
    }
}

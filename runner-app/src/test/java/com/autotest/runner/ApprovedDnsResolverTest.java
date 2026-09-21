package com.autotest.runner;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.URI;
import java.net.UnknownHostException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovedDnsResolverTest {

    @Test
    void requiresEveryResolvedAddressToBeApproved() throws Exception {
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> new InetAddress[]{
                InetAddress.getByName("203.0.113.10"), InetAddress.getByName("198.51.100.20")});

        var resolved = resolver.resolve(URI.create("https://api.example.test/health"),
                List.of("api.example.test"));

        assertEquals(2, resolved.addresses().size());
        assertEquals("api.example.test", resolved.host());
    }

    @Test
    void rejectsAnUnapprovedGlobalAddressWhenThePolicyUsesAnAddressRule() throws Exception {
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> new InetAddress[]{
                InetAddress.getByName("172.31.0.11"), InetAddress.getByName("ffff::1")});

        assertThrows(ApprovedDnsResolver.DnsPolicyException.class,
                () -> resolver.resolve(URI.create("http://mixed-target:8080/health"),
                        List.of("mixed-target:8080", "172.31.0.0/24:8080")));
    }

    @Test
    void rejectsDnsRebindingWhenAResolvedAddressDoesNotMatchTheSnapshot() throws Exception {
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> new InetAddress[]{
                InetAddress.getByName("203.0.113.10"), InetAddress.getByName("127.0.0.1")});

        assertThrows(ApprovedDnsResolver.DnsPolicyException.class,
                () -> resolver.resolve(URI.create("https://api.example.test/health"),
                        List.of("api.example.test")));
    }

    @Test
    void permitsAnExplicitAddressRuleAndRejectsUnknownHosts() throws Exception {
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> {
            if (host.equals("127.0.0.1")) return new InetAddress[]{InetAddress.getByName(host)};
            throw new UnknownHostException(host);
        });

        var resolved = resolver.resolve(URI.create("http://127.0.0.1/health"), List.of("127.0.0.1"));
        assertEquals("127.0.0.1", resolved.host());
        assertThrows(ApprovedDnsResolver.DnsPolicyException.class,
                () -> resolver.resolve(URI.create("https://missing.example.test/health"), List.of("example.test")));
    }

    @Test
    void failsClosedWhenASecondLookupChangesTheAddressSet() throws Exception {
        InetAddress first = InetAddress.getByName("203.0.113.10");
        InetAddress second = InetAddress.getByName("198.51.100.20");
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host ->
                calls.getAndIncrement() == 0 ? new InetAddress[]{first} : new InetAddress[]{second});
        var approved = resolver.resolve(URI.create("https://api.example.test/health"), List.of("api.example.test"));
        assertThrows(ApprovedDnsResolver.DnsPolicyException.class, () ->
                resolver.verifyStable(URI.create("https://api.example.test/health"), List.of("api.example.test"), approved));
    }

    @Test
    void preservesAStableSortedDeduplicatedAddressSnapshotAndAppliesPorts() throws Exception {
        InetAddress second = InetAddress.getByName("198.51.100.20");
        InetAddress first = InetAddress.getByName("192.0.2.10");
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> new InetAddress[]{second, first, first});

        var resolved = resolver.resolve(URI.create("https://api.example.test:8443/health"),
                List.of("api.example.test:8443"));

        assertEquals(List.of(first, second), resolved.addresses());
        assertThrows(ApprovedDnsResolver.DnsPolicyException.class, () ->
                resolver.resolve(URI.create("https://api.example.test:8444/health"), List.of("api.example.test:8443")));
    }

    @Test
    void doesNotPerformDnsLookupForAnIpLiteral() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ApprovedDnsResolver resolver = new ApprovedDnsResolver(host -> {
            calls.incrementAndGet();
            throw new UnknownHostException(host);
        });

        var resolved = resolver.resolve(URI.create("http://127.0.0.1/health"), List.of("127.0.0.1"));

        assertEquals(0, calls.get());
        assertEquals(List.of(InetAddress.getByName("127.0.0.1")), resolved.addresses());
    }

    @Test
    void directDnsLookupQueriesBothFamiliesOnEveryResolve() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            List<Integer> queryTypes = new CopyOnWriteArrayList<>();
            Thread responder = new Thread(() -> serveDns(server, queryTypes, 4), "dns-test-server");
            responder.start();

            var lookup = ApprovedDnsResolver.directLookup(InetAddress.getLoopbackAddress(), server.getLocalPort());
            assertEquals(1, lookup.lookup("api.example.test").length);
            assertEquals(1, lookup.lookup("api.example.test").length);

            responder.join(3_000);
            assertEquals(List.of(1, 28, 1, 28), queryTypes);
        }
    }

    @Test
    void rejectsDnsResponseFromAnUnexpectedUdpSource() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try {
                    try (DatagramSocket forgedSource = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
                        byte[] requestBytes = new byte[2048];
                        DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
                        server.receive(request);
                        byte[] response = responseWithAnswer(requestBytes, false);
                        forgedSource.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                    }
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }, "dns-forged-source");
            responder.start();

            var lookup = ApprovedDnsResolver.directLookup(InetAddress.getLoopbackAddress(), server.getLocalPort());
            assertThrows(UnknownHostException.class, () -> lookup.lookup("api.example.test"));
            responder.join(3_000);
        }
    }

    @Test
    void rejectsDnsResponseWithAMismatchedQuestion() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try {
                    byte[] requestBytes = new byte[2048];
                    DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
                    server.receive(request);
                    byte[] response = responseWithAnswer(requestBytes, true);
                    server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }, "dns-mismatched-question");
            responder.start();

            var lookup = ApprovedDnsResolver.directLookup(InetAddress.getLoopbackAddress(), server.getLocalPort());
            assertThrows(UnknownHostException.class, () -> lookup.lookup("api.example.test"));
            responder.join(3_000);
        }
    }

    @Test
    void rejectsDnsResponseWithAMismatchedTransactionId() throws Exception {
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread responder = new Thread(() -> {
                try {
                    byte[] requestBytes = new byte[2048];
                    DatagramPacket request = new DatagramPacket(requestBytes, requestBytes.length);
                    server.receive(request);
                    byte[] response = responseWithAnswer(requestBytes, false);
                    response[1] ^= 1;
                    server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                } catch (IOException exception) {
                    throw new RuntimeException(exception);
                }
            }, "dns-mismatched-transaction");
            responder.start();

            var lookup = ApprovedDnsResolver.directLookup(InetAddress.getLoopbackAddress(), server.getLocalPort());
            assertThrows(UnknownHostException.class, () -> lookup.lookup("api.example.test"));
            responder.join(3_000);
        }
    }

    private static byte[] responseWithAnswer(byte[] request, boolean mismatchedQuestion) {
        int questionEnd = questionEnd(request);
        byte[] question = Arrays.copyOfRange(request, 12, questionEnd + 5);
        int queryType = ((request[questionEnd + 1] & 0xff) << 8) | (request[questionEnd + 2] & 0xff);
        if (mismatchedQuestion) {
            int mismatch = queryType == 1 ? 28 : 1;
            question[question.length - 4] = (byte) (mismatch >>> 8);
            question[question.length - 3] = (byte) mismatch;
        }
        byte[] address = queryType == 1
                ? new byte[]{(byte) 203, 0, 113, 10}
                : new byte[]{0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        byte[] response = new byte[12 + question.length + 12 + address.length];
        response[0] = request[0];
        response[1] = request[1];
        response[2] = (byte) 0x81;
        response[3] = (byte) 0x80;
        response[5] = 1;
        response[7] = 1;
        System.arraycopy(question, 0, response, 12, question.length);
        int answerOffset = 12 + question.length;
        response[answerOffset] = (byte) 0xc0;
        response[answerOffset + 1] = 0x0c;
        response[answerOffset + 3] = (byte) queryType;
        response[answerOffset + 5] = 1;
        response[answerOffset + 11] = (byte) address.length;
        System.arraycopy(address, 0, response, answerOffset + 12, address.length);
        return response;
    }

    private static int questionEnd(byte[] packet) {
        int offset = 12;
        while ((packet[offset] & 0xff) != 0) offset += (packet[offset] & 0xff) + 1;
        return offset;
    }

    private static void serveDns(DatagramSocket server, List<Integer> queryTypes, int count) {
        try {
            for (int i = 0; i < count; i++) {
                byte[] buffer = new byte[2048];
                DatagramPacket request = new DatagramPacket(buffer, buffer.length);
                server.receive(request);
                int questionEnd = 12;
                while ((buffer[questionEnd] & 0xff) != 0) questionEnd += (buffer[questionEnd] & 0xff) + 1;
                int qtype = ((buffer[questionEnd + 1] & 0xff) << 8) | (buffer[questionEnd + 2] & 0xff);
                queryTypes.add(qtype);
                byte[] question = Arrays.copyOfRange(buffer, 12, questionEnd + 5);
                ByteArrayOutputStream answer = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(answer)) {
                    if (qtype == 1) {
                        output.writeShort(0xc00c);
                        output.writeShort(1);
                        output.writeShort(1);
                        output.writeInt(5);
                        output.writeShort(4);
                        output.write(new byte[]{(byte) 203, 0, 113, 10});
                    }
                }
                byte[] response = new byte[12 + question.length + answer.size()];
                response[0] = buffer[0];
                response[1] = buffer[1];
                response[2] = (byte) 0x81;
                response[3] = (byte) 0x80;
                response[5] = 1;
                response[7] = (byte) (qtype == 1 ? 1 : 0);
                System.arraycopy(question, 0, response, 12, question.length);
                System.arraycopy(answer.toByteArray(), 0, response, 12 + question.length, answer.size());
                server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }
}

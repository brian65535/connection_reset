import io.vertx.core.*;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.dns.AddressResolverOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.ConnectionManager;
import io.vertx.core.http.impl.HttpClientImpl;
import io.vertx.ext.unit.*;
import io.vertx.ext.unit.report.ReportOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeoutException;

/**
 * Unit test to reproduce the case that HttpClientRequest.reset()
 * does NOT properly reset the client connection in Vert.x 3.4.1
 */
public class ResetTest {

    private static Logger logger = LoggerFactory.getLogger(ResetTest.class);

    private static final String PUBLIC_IP;
    private static final String LOOPBACK_IP = "127.0.0.1";
    private static final String FAKE_HOST = "non.exist.com.us";
    static {
        try {
            PUBLIC_IP = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private static boolean usePublicIp = false;

    private static byte[] DNS_HEADER = new byte[]{
            -128, 0,    // FLAGS
            0, 1,       // QDCOUNT
            0, 1,       // ANCOUNT
            0, 0,       // NSCOUNT
            0, 0        // ARCOUNT
    };

    private static byte[] DNS_ANSWER = new byte[]{
            0, 1,       // TYPE
            0, 1,       // CLASS
            0, 0, 0, 0, // TTL
            0, 4,       // LENGTH
    };

    private byte[] generateDNSResponse(byte[] id, byte[] name, byte[] ques, String ip) {
        byte[] r = new byte[name.length + ques.length + 26];
        System.arraycopy(id, 0, r, 0, 2);
        System.arraycopy(DNS_HEADER, 0, r, 2, 10);
        System.arraycopy(ques, 0, r, 12, ques.length);
        System.arraycopy(name, 0, r, 12 + ques.length, name.length);
        System.arraycopy(DNS_ANSWER, 0, r, 12 + ques.length + name.length, 10);
        int index = 12 + ques.length + name.length + 10;
        for (String b : ip.split("\\.")) {
            r[index++] = (byte) Integer.parseInt(b);
        }
        return r;
    }

    private Handler<DatagramPacket> dnsServerHandler(DatagramSocket socket) {
        return p -> {
            String ip = usePublicIp ? PUBLIC_IP : LOOPBACK_IP;
            byte[] bytes = p.data().getBytes();

            int pos = 12;
            StringJoiner sj = new StringJoiner(".");
            while (true) {
                int l = (int) bytes[pos++];
                if (l == 0) break;
                sj.add(new String(bytes, pos, l));
                pos += l;
            }
            String question = sj.toString();
            logger.info("DNS question: {}", question);

            byte[] id = new byte[2];
            byte[] name = new byte[pos - 12];
            byte[] ques = new byte[pos - 12 + 4];
            System.arraycopy(bytes, 0, id, 0,  2);
            System.arraycopy(bytes, 12, name, 0, pos - 12);
            System.arraycopy(bytes, 12, ques, 0, pos - 12 + 4);
            byte[] r = generateDNSResponse(id, name, ques, ip);
            logger.info("DNS answer: {}", ip);
            socket.send(Buffer.buffer(r), p.sender().port(), p.sender().host(), r2 -> {});
        };
    }

    private Handler<HttpServerRequest> webServerHandler(final Vertx vertx) {
        return req -> {
            logger.info("web server got request with local addr: {}", req.localAddress().host());
            if (LOOPBACK_IP.equals(req.localAddress().host())) {
                vertx.setTimer(3000, id -> {
                    req.response().end("Hello!");
                });
            } else {
                req.response().end("Hello!");
            }
        };
    }

    private <T> Handler<AsyncResult<T>> completeHandler(TestContext testContext, Future future, String msg) {
        return r -> {
            if (r.succeeded()) {
                logger.info(msg);
                future.complete();
            } else {
                testContext.fail(r.cause());
            }
        };
    }

    static void makeRequest(HttpClient client, final Future<String> future, String seq) {
        HttpClientRequest req = client.get(8080, FAKE_HOST, "/")
                .setTimeout(2000)
                .handler(res -> {
                    logger.info("{}: client got response, status: {}", seq, res.statusCode());
                    res.bodyHandler(buf -> {
                        logger.info("{}: response body: {}", seq, buf.toString());
                        future.complete(buf.toString());
                    }).exceptionHandler(future::fail);
                });
        req.exceptionHandler(t -> {
            if (t instanceof TimeoutException) {
                logger.info("{}: request timeout", seq);
                boolean resetStatus = req.reset();
                logger.info("{}: request reset {}", seq, resetStatus ? "succeeded" : "failed");
                future.complete(null);
            } else if (!future.isComplete()) {
                future.fail(t);
            }
        }).end();
    }

    public static class ClientVerticle extends AbstractVerticle {
        private HttpClient client;
        @Override
        public void start() throws Exception {
            client = vertx.createHttpClient();
            Object[] maps = getInternalMaps(client);

            vertx.eventBus().consumer("client", msg -> {
                String seq = msg.body().toString();
                logger.info("{}: Client queueMap size: {}, connectionMap size: {}", seq, ((Map) maps[0]).size(), ((Map) maps[1]).size());

                Future<String> future = Future.future();
                makeRequest(client, future, seq);
                future.setHandler(r -> msg.reply(r.result()));
            });
        }
    }

    static Object[] getInternalMaps(HttpClient httpClient) throws Exception {
        HttpClientImpl httpClientImpl = (HttpClientImpl) httpClient;
        Field connectionManagerField = HttpClientImpl.class.getDeclaredField("connectionManager");
        connectionManagerField.setAccessible(true);
        ConnectionManager connectionManager = (ConnectionManager) connectionManagerField.get(httpClientImpl);
        Field requestQMField = ConnectionManager.class.getDeclaredField("requestQM");
        requestQMField.setAccessible(true);
        Object requestQM = requestQMField.get(connectionManager);

        Class QueueManager = ConnectionManager.class.getDeclaredClasses()[3];
        Field queueMapField = QueueManager.getDeclaredField("queueMap");
        queueMapField.setAccessible(true);
        Field connectionMapField = QueueManager.getDeclaredField("connectionMap");
        connectionMapField.setAccessible(true);
        return new Object[]{
                queueMapField.get(requestQM),
                connectionMapField.get(requestQM)
        };
    }

    @Test
    public void test() throws Exception {

        TestSuite testSuite = TestSuite.create("");
        testSuite.test("Normal", testContext -> {
            Async async = testContext.async();

            Vertx vertx = Vertx.vertx(new VertxOptions().setAddressResolverOptions(
                    new AddressResolverOptions().addServer("127.0.0.1:5053")));

            Future<Void> futureDns = Future.future();
            DatagramSocket dnsServer = vertx.createDatagramSocket();
            dnsServer.handler(dnsServerHandler(dnsServer))
                    .listen(5053, "0.0.0.0", completeHandler(testContext, futureDns, "DNS server started"));

            Future<Void> futureWeb = Future.future();
            vertx.createHttpServer().requestHandler(webServerHandler(vertx))
                    .listen(8080, completeHandler(testContext, futureWeb, "Web server started"));

            Future<String> futureClient = Future.future();
            vertx.deployVerticle(new ClientVerticle(), completeHandler(testContext, futureClient, "Client ready"));

            CompositeFuture futureAll = CompositeFuture.all(futureDns, futureWeb, futureClient);
            futureAll.setHandler(r -> {

                logger.info("==========");
                logger.info("Making 1st client request to {}:4080", FAKE_HOST);
                vertx.eventBus().send("client", "1", reply -> {

                    logger.info("==========");
                    logger.info("Sleep 5 seconds");
                    vertx.setTimer(5000, id -> {

                        usePublicIp = !usePublicIp;
                        logger.info("==========");
                        logger.info("Switched DNS answer, now answer is {}", usePublicIp ? PUBLIC_IP : LOOPBACK_IP);

                        logger.info("==========");
                        logger.info("Making 2nd client request to {}", FAKE_HOST);
                        Future<Void> future2 = Future.future();
                        vertx.eventBus().send("client", "2", reply2 -> future2.complete());

                        logger.info("Making 3rd client request to {}", FAKE_HOST);
                        Future<Void> future3 = Future.future();
                        vertx.eventBus().send("client", "3", reply3 -> future3.complete());

                        CompositeFuture.all(future2, future3).setHandler(h -> {
                            logger.info("==========");
                            logger.info("DONE");
                            vertx.close(v -> async.complete());
                        });


                    });
                });
            });
        });

        Completion completion = testSuite.run(new TestOptions().addReporter(new ReportOptions().setTo("console")));
        completion.awaitSuccess();
    }

}

// SPDX-License-Identifier: BSD-3-Clause
package org.xbill.DNS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

@ExtendWith(VertxExtension.class)
class DohResolverTest {
  private DohResolver resolver;
  private final Name queryName = Name.fromConstantString("example.com.");
  private final Record qr = Record.newRecord(queryName, Type.A, DClass.IN);
  private final Message qm = Message.newQuery(qr);
  private final Message a = new Message();
  private boolean allRequestsUseTimeout = true;

  @BeforeEach
  void beforeEach() throws UnknownHostException {
    resolver = new DohResolver("http://localhost");
    Record ar =
        new ARecord(
            Name.fromConstantString("example.com."),
            DClass.IN,
            3600,
            InetAddress.getByName("127.0.0.1"));
    a.addRecord(qr, Section.QUESTION);
    a.addRecord(ar, Section.ANSWER);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void simpleResolve(boolean usePost, Vertx vertx, VertxTestContext context) {
    resolver.setUsePost(usePost);
    setupResolverWithServer(Duration.ZERO, 200, 1, vertx, context)
        .onSuccess(
            server ->
                Future.fromCompletionStage(resolver.sendAsync(qm))
                    .onComplete(
                        context.succeeding(
                            result ->
                                context.verify(
                                    () -> {
                                      assertEquals(Rcode.NOERROR, result.getHeader().getRcode());
                                      assertEquals(0, result.getHeader().getID());
                                      assertEquals(queryName, result.getQuestion().getName());
                                      context.completeNow();
                                    }))));
  }

  @Test
  void timeoutResolve(Vertx vertx, VertxTestContext context) {
    resolver.setTimeout(Duration.ofSeconds(1));
    setupResolverWithServer(Duration.ofSeconds(5), 200, 1, vertx, context)
        .onSuccess(
            server ->
                Future.fromCompletionStage(resolver.sendAsync(qm))
                    .onComplete(
                        context.failing(
                            ex ->
                                context.verify(
                                    () -> {
                                      assertTrue(
                                          ex.getCause() instanceof TimeoutException
                                              || ex.getMessage().contains("timed out"));
                                      context.completeNow();
                                    }))));
  }

  @Test
  void servfailResolve(Vertx vertx, VertxTestContext context) {
    setupResolverWithServer(Duration.ZERO, 301, 1, vertx, context)
        .onSuccess(
            server ->
                Future.fromCompletionStage(resolver.sendAsync(qm))
                    .onComplete(
                        context.succeeding(
                            result ->
                                context.verify(
                                    () -> {
                                      assertEquals(Rcode.SERVFAIL, result.getHeader().getRcode());
                                      context.completeNow();
                                    }))));
  }

  @Test
  void limitRequestsResolve(Vertx vertx, VertxTestContext context) {
    resolver = new DohResolver("http://localhost", 5, Duration.ofMinutes(2));
    int requests = 100;
    Checkpoint cpPass = context.checkpoint(requests);
    setupResolverWithServer(Duration.ofMillis(100), 200, 5, vertx, context)
        .onSuccess(
            server -> {
              for (int i = 0; i < requests; i++) {
                resolver
                    .sendAsync(qm)
                    .whenComplete(
                        (result, ex) -> {
                          if (ex == null) {
                            cpPass.flag();
                          } else {
                            context.failNow(ex);
                          }
                        });
              }
            });
  }

  @Test
  void initialRequestSlowResolve(Vertx vertx, VertxTestContext context) {
    resolver = new DohResolver("http://localhost", 2, Duration.ofMinutes(2));
    int requests = 20;
    allRequestsUseTimeout = false;
    Checkpoint cpPass = context.checkpoint(requests);
    setupResolverWithServer(Duration.ofSeconds(1), 200, 2, vertx, context)
        .onSuccess(
            server -> {
              for (int i = 0; i < requests; i++) {
                resolver
                    .sendAsync(qm)
                    .whenComplete(
                        (result, ex) -> {
                          if (ex == null) {
                            cpPass.flag();
                          } else {
                            context.failNow(ex);
                          }
                        });
              }
            });
  }

  @Test
  void initialRequestTimeoutResolve(Vertx vertx, VertxTestContext context) {
    resolver = new DohResolver("http://localhost", 2, Duration.ofMinutes(2));
    resolver.setTimeout(Duration.ofSeconds(1));
    int requests = 20;
    allRequestsUseTimeout = false;
    Checkpoint cpPass = context.checkpoint(requests - 1);
    Checkpoint cpFail = context.checkpoint();
    setupResolverWithServer(Duration.ofSeconds(2), 200, 2, vertx, context)
        .onSuccess(
            server -> {
              resolver
                  .sendAsync(qm)
                  .whenComplete(
                      (result, ex) -> {
                        if (ex == null) {
                          context.failNow("First request succeeded");
                        } else {
                          cpFail.flag();
                        }
                      });
              vertx.setTimer(
                  1000,
                  timer -> {
                    for (int i = 0; i < requests - 1; i++) {
                      resolver
                          .sendAsync(qm)
                          .whenComplete(
                              (result, ex) -> {
                                if (ex == null) {
                                  cpPass.flag();
                                } else {
                                  context.failNow("Request failed");
                                }
                              });
                    }
                  });
            });
  }

  private Future<HttpServer> setupResolverWithServer(
      Duration responseDelay,
      int statusCode,
      int maxConcurrentRequests,
      Vertx vertx,
      VertxTestContext context) {
    return setupServer(qm, a, responseDelay, statusCode, maxConcurrentRequests, context, vertx)
        .onSuccess(server -> resolver.setUriTemplate("http://localhost:" + server.actualPort()));
  }

  @EnabledForJreRange(
      min = JRE.JAVA_9,
      disabledReason = "Java 8 implementation doesn't have the initial request guard")
  @Test
  void initialRequestGuardIfIdleConnectionTimeIsLargerThanSystemNanoTime(
      Vertx vertx, VertxTestContext context) {
    AtomicLong startNanos = new AtomicLong(System.nanoTime());
    resolver = spy(new DohResolver("http://localhost", 2, Duration.ofMinutes(2)));
    resolver.setTimeout(Duration.ofSeconds(1));
    // Simulate a nanoTime value that is lower than the idle timeout
    doAnswer((Answer<Long>) invocationOnMock -> System.nanoTime() - startNanos.get())
        .when(resolver)
        .getNanoTime();

    // Just add a 100ms delay before responding to the 1st call
    // to simulate a 'concurrent doh request' for the 2nd call,
    // then let the fake dns server respond to the 2nd call ASAP.
    allRequestsUseTimeout = false;

    // idleConnectionTimeout = 2s, lastRequest = 0L
    // Ensure idleConnectionTimeout < System.nanoTime() - lastRequest (3s)

    // Timeline:
    //         |<-------- 100ms -------->|
    //         ↑                         ↑
    //  1st call sent              response of 1st call
    //         |20ms|<------ 80ms ------>|<------ few millis ------->|
    //              ↑ wait until 1st call ↑                           ↑
    //       2nd call begin         2nd call sent            response of 2nd call

    AtomicBoolean firstCallCompleted = new AtomicBoolean(false);

    setupResolverWithServer(Duration.ofMillis(100L), 200, 2, vertx, context)
        .onSuccess(
            server -> {
              // First call
              CompletionStage<Message> firstCall = resolver.sendAsync(qm);
              // Ensure second call was made after first call and uses a different query
              startNanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(20));
              CompletionStage<Message> secondCall = resolver.sendAsync(Message.newQuery(qr));

              Future.fromCompletionStage(firstCall)
                  .onComplete(
                      context.succeeding(
                          result ->
                              context.verify(
                                  () -> {
                                    assertEquals(Rcode.NOERROR, result.getHeader().getRcode());
                                    assertEquals(0, result.getHeader().getID());
                                    assertEquals(queryName, result.getQuestion().getName());
                                    firstCallCompleted.set(true);
                                  })));

              Future.fromCompletionStage(secondCall)
                  .onComplete(
                      context.succeeding(
                          result ->
                              context.verify(
                                  () -> {
                                    assertTrue(firstCallCompleted.get());
                                    assertEquals(Rcode.NOERROR, result.getHeader().getRcode());
                                    assertEquals(0, result.getHeader().getID());
                                    assertEquals(queryName, result.getQuestion().getName());
                                    // Complete context after the 2nd call was completed.
                                    context.completeNow();
                                  })));
            });
  }

  private Future<HttpServer> setupServer(
      Message expectedDnsRequest,
      Message dnsResponse,
      Duration serverProcessingTime,
      int statusCode,
      int maxConcurrentRequests,
      VertxTestContext context,
      Vertx vertx) {
    HttpVersion version =
        System.getProperty("java.version").startsWith("1.")
            ? HttpVersion.HTTP_1_1
            : HttpVersion.HTTP_2;
    AtomicInteger requestCount = new AtomicInteger(0);
    AtomicInteger concurrentRequests = new AtomicInteger(0);
    return vertx
        .createHttpServer(
            new HttpServerOptions().setAlpnVersions(Collections.singletonList(version)))
        .requestHandler(
            httpRequest -> {
              int thisRequestNum = requestCount.incrementAndGet();
              int count = concurrentRequests.incrementAndGet();
              if (count > maxConcurrentRequests) {
                context.failNow("Concurrent requests exceeded");
                return;
              }

              httpRequest.bodyHandler(
                  body -> {
                    context.verify(
                        () -> {
                          assertEquals(
                              "application/dns-message",
                              httpRequest.getHeader(HttpHeaderNames.CONTENT_TYPE));
                          byte[] actualDnsRequestBytes;
                          if (httpRequest.method() == HttpMethod.POST) {
                            actualDnsRequestBytes = body.getBytes();
                          } else {
                            actualDnsRequestBytes =
                                Base64.getDecoder().decode(httpRequest.getParam("dns"));
                          }

                          Message actualDnsRequest = new Message(actualDnsRequestBytes);
                          assertEquals(0, actualDnsRequest.getHeader().getID());
                          assertEquals(
                              expectedDnsRequest.getQuestion(), actualDnsRequest.getQuestion());
                        });
                    Message dnsResponseCopy = dnsResponse.clone();
                    dnsResponseCopy.getHeader().setID(0);
                    if (!serverProcessingTime.isZero()
                        && (thisRequestNum == 1 || allRequestsUseTimeout)) {
                      vertx.setTimer(
                          serverProcessingTime.toMillis(),
                          timer -> {
                            concurrentRequests.decrementAndGet();
                            httpRequest
                                .response()
                                .setStatusCode(statusCode)
                                .end(Buffer.buffer(dnsResponseCopy.toWire()));
                          });
                    } else {
                      concurrentRequests.decrementAndGet();
                      httpRequest
                          .response()
                          .setStatusCode(statusCode)
                          .end(Buffer.buffer(dnsResponseCopy.toWire()));
                    }
                  });
            })
        .listen(0);
  }
}

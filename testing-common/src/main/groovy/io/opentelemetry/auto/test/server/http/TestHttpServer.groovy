/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.auto.test.server.http

import static io.opentelemetry.auto.test.server.http.HttpServletRequestExtractAdapter.GETTER
import static io.opentelemetry.trace.Span.Kind.SERVER

import io.opentelemetry.OpenTelemetry
import io.opentelemetry.auto.test.asserts.InMemoryExporterAssert
import io.opentelemetry.auto.test.asserts.TraceAssert
import io.opentelemetry.auto.test.utils.PortUtils
import io.opentelemetry.instrumentation.api.decorator.BaseDecorator
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.trace.Span
import io.opentelemetry.trace.Tracer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import org.eclipse.jetty.http.HttpMethods
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.AbstractHandler
import org.eclipse.jetty.server.handler.HandlerList

class TestHttpServer implements AutoCloseable {

  private static final Tracer TRACER = OpenTelemetry.getTracer("io.opentelemetry.auto")

  static TestHttpServer httpServer(@DelegatesTo(value = TestHttpServer, strategy = Closure.DELEGATE_FIRST) Closure spec) {

    def server = new TestHttpServer()
    def clone = (Closure) spec.clone()
    clone.delegate = server
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(server)
    server.start()
    return server
  }

  private final Server internalServer
  private HandlersSpec handlers


  private URI address
  private final AtomicReference<HandlerApi.RequestApi> last = new AtomicReference<>()

  private TestHttpServer() {
    internalServer = new Server(0)
    internalServer.connectors.each {
      it.setHost('localhost')
    }
  }

  def start() {
    if (internalServer.isStarted()) {
      return
    }

    assert handlers != null: "handlers must be defined"
    def handlerList = new HandlerList()
    handlerList.handlers = handlers.configured
    internalServer.handler = handlerList
    internalServer.start()
    // set after starting, otherwise two callbacks get added.
    internalServer.stopAtShutdown = true

    def port = internalServer.connectors[0].localPort
    address = new URI("http://localhost:${port}")

    PortUtils.waitForPortToOpen(port, 20, TimeUnit.SECONDS)
    System.out.println("Started server $this on port ${address.getPort()}")
    return this
  }

  def stop() {
    System.out.println("Stopping server $this on port $address.port")
    internalServer.stop()
    return this
  }

  void close() {
    stop()
  }

  URI getAddress() {
    return address
  }

  def getLastRequest() {
    return last.get()
  }

  void handlers(@DelegatesTo(value = HandlersSpec, strategy = Closure.DELEGATE_FIRST) Closure spec) {
    assert handlers == null: "handlers already defined"
    handlers = new HandlersSpec()

    def clone = (Closure) spec.clone()
    clone.delegate = handlers
    clone.resolveStrategy = Closure.DELEGATE_FIRST
    clone(handlers)
  }

  static distributedRequestTrace(InMemoryExporterAssert traces, int index, SpanData parentSpan = null) {
    traces.trace(index, 1) {
      distributedRequestSpan(it, 0, parentSpan)
    }
  }

  static distributedRequestSpan(TraceAssert trace, int index, SpanData parentSpan = null) {
    trace.span(index) {
      operationName "test-http-server"
      errored false
      if (parentSpan == null) {
        parent()
      } else {
        childOf(parentSpan)
      }
      attributes {
      }
    }
  }

  private class HandlersSpec {

    List<Handler> configured = []

    void get(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(HttpMethods.GET, path, spec)
    }

    void post(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(HttpMethods.POST, path, spec)
    }

    void put(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      assert path != null
      configured << new HandlerSpec(HttpMethods.PUT, path, spec)
    }

    void prefix(String path, @DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      configured << new PrefixHandlerSpec(path, spec)
    }

    void all(@DelegatesTo(value = HandlerApi, strategy = Closure.DELEGATE_FIRST) Closure<Void> spec) {
      configured << new AllHandlerSpec(spec)
    }
  }

  private class HandlerSpec extends AllHandlerSpec {

    private final String method
    private final String path

    private HandlerSpec(String method, String path, Closure<Void> spec) {
      super(spec)
      this.method = method
      this.path = path.startsWith("/") ? path : "/" + path
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (request.method == method && target == path) {
        send(baseRequest, response)
      }
    }
  }

  private class PrefixHandlerSpec extends AllHandlerSpec {

    private final String prefix

    private PrefixHandlerSpec(String prefix, Closure<Void> spec) {
      super(spec)
      this.prefix = prefix.startsWith("/") ? prefix : "/" + prefix
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      if (target.startsWith(prefix)) {
        send(baseRequest, response)
      }
    }
  }

  private class AllHandlerSpec extends AbstractHandler {
    protected final Closure<Void> spec

    protected AllHandlerSpec(Closure<Void> spec) {
      this.spec = spec
    }

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      send(baseRequest, response)
    }

    protected void send(Request baseRequest, HttpServletResponse response) {
      def api = new HandlerApi(baseRequest, response)
      last.set(api.request)

      def clone = (Closure) spec.clone()
      clone.delegate = api
      clone.resolveStrategy = Closure.DELEGATE_FIRST

      try {
        clone(api)
      } catch (Exception e) {
        api.response.status(500).send(e.getMessage())
      }
    }
  }

  class HandlerApi {
    private final Request req
    private final HttpServletResponse resp

    private HandlerApi(Request request, HttpServletResponse response) {
      this.req = request
      this.resp = response
    }

    def getRequest() {
      return new RequestApi()
    }


    def getResponse() {
      return new ResponseApi()
    }

    void redirect(String uri) {
      resp.sendRedirect(uri)
      req.handled = true
    }

    void handleDistributedRequest() {
      boolean isTestServer = true
      if (request.getHeader("is-test-server") != null) {
        isTestServer = Boolean.parseBoolean(request.getHeader("is-test-server"))
      }
      if (isTestServer) {
        final Span.Builder spanBuilder = TRACER.spanBuilder("test-http-server").setSpanKind(SERVER)
        spanBuilder.setParent(BaseDecorator.extract(req, GETTER))
        final Span span = spanBuilder.startSpan()
        span.end()
      }
    }

    class RequestApi {
      def path = req.pathInfo
      def headers = new Headers(req)
      def contentLength = req.contentLength
      def contentType = req.contentType?.split(";")

      def body = req.inputStream.bytes

      def getPath() {
        return path
      }

      def getContentLength() {
        return contentLength
      }

      def getContentType() {
        return contentType ? contentType[0] : null
      }

      def getHeaders() {
        return headers
      }

      String getHeader(String header) {
        return headers[header]
      }

      def getBody() {
        return body
      }

      def getText() {
        return new String(body)
      }
    }

    class ResponseApi {
      private int status = 200

      ResponseApi status(int status) {
        this.status = status
        return this
      }

      void send() {
        assert !req.handled
        req.contentType = "text/plain;charset=utf-8"
        resp.status = status
        req.handled = true
      }

      void send(String body) {
        assert body != null

        send()
        resp.setContentLength(body.bytes.length)
        resp.writer.print(body)
      }

      void send(String body, String contentType) {
        assert contentType != null
        resp.setContentType(contentType)
        send(body)
      }
    }

    static class Headers {
      private final Map<String, String> headers

      private Headers(Request request) {
        this.headers = [:]
        request.getHeaderNames().each {
          headers.put(it, request.getHeader(it))
        }
      }

      def get(String header) {
        return headers[header]
      }
    }
  }
}

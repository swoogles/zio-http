package zio.http.middleware

import zio._
import zio.http.Middleware._
import zio.http.Status.{InternalServerError, Ok}
import zio.http._
import zio.http.internal.HttpAppTestExtensions
import zio.metrics.{Metric, MetricLabel}
import zio.test.Assertion._
import zio.test.TestAspect.flaky
import zio.test._

object WebSpec extends ZIOSpecDefault with HttpAppTestExtensions { self =>
  private val app  = Http.collectZIO[Request] { case Method.GET -> !! / "health" =>
    ZIO.succeed(Response.ok).delay(1 second)
  }
  private val midA = Middleware.addHeader("X-Custom", "A")
  private val midB = Middleware.addHeader("X-Custom", "B")

  def spec = suite("HttpMiddleware")(
    suite("metrics")(
      test("count requests") {
        val localLabel                     = MetricLabel("test", "count requests")
        val app = (Http.ok @@ metricsM(localLabel))(Request())
        for {
          _     <- app.repeatN(2)
          count <- Metric.counter("totalRequests").tagged(localLabel).value
        } yield assertTrue(count.count == 3)
      },
      test("track response times histogram") {
        val localLabel                     = MetricLabel("test", "response times")
        val localMetrics                   = metricsM(localLabel)

        def delayedApp(duration: Duration) =
          for {
            process <- (Http.ok.delay(duration) @@ localMetrics)(Request()).fork
            _   <- TestClock.adjust(10 seconds)
            _ <- process.join
          } yield ()

        for {
          _         <- delayedApp(1.second)
          _         <- delayedApp(10.millis)
          _         <- delayedApp(200.millis)
          durations <- requestDuration.value
          _         <- ZIO.debug(durations.max)
          _         <- ZIO.debug(durations.buckets)
        } yield assertTrue(durations.max == 1000)
      },
      test("count response codes") {
        val label        = MetricLabel("test", "responseCodeTracking")
        val localMetrics = metricsM(label)
        val localCounter = Metric
          .counter("responses")
          .tagged(label)

        def app(response: Response) =
          Http(response) @@ localMetrics

        for {
          _                            <- runApp(app(Response.ok))
          _                            <- runApp(app(Response.fromHttpError(HttpError.InternalServerError("No good"))))
          _                            <- runApp(app(Response.ok))
          _                            <- runApp(app(Response.redirect("newLocation")))
          okResponses                  <- localCounter
            .tagged("ResponseCode", Ok.toString)
            .value
          internalServerErrorResponses <- localCounter
            .tagged("ResponseCode", InternalServerError.toString)
            .value
        } yield assertTrue(okResponses.count == 2 && internalServerErrorResponses.count == 1)
      },
      test("gauge concurrent requests") {
        val label        = MetricLabel("test", "gauge inflightRequests")
        def delayedApp(duration: Duration) = (Http.ok.delay(duration) @@ metricsM(label))(Request(url = URL(!! / "health")))

        for {
          _ <- ZIO.forkAllDiscard(List(1,2).map(_ => delayedApp(1.second)))
          _              <- Clock.ClockLive.sleep(10.millis)
          activeRequests <- Metric.gauge("inflightRequests").tagged(label).value
          _              <- TestClock.adjust(10 seconds)
        } yield assertTrue(activeRequests.value == 2)
      } @@ flaky,
    ),
    suite("headers suite")(
      test("addHeaders") {
        val middleware = addHeaders(Headers("KeyA", "ValueA") ++ Headers("KeyB", "ValueB"))
        val headers    = (Http.ok @@ middleware).headerValues
        assertZIO(headers(Request()))(contains("ValueA") && contains("ValueB"))
      },
      test("addHeader") {
        val middleware = addHeader("KeyA", "ValueA")
        val headers    = (Http.ok @@ middleware).headerValues
        assertZIO(headers(Request()))(contains("ValueA"))
      },
      test("updateHeaders") {
        val middleware = updateHeaders(_ => Headers("KeyA", "ValueA"))
        val headers    = (Http.ok @@ middleware).headerValues
        assertZIO(headers(Request()))(contains("ValueA"))
      },
      test("removeHeader") {
        val middleware = removeHeader("KeyA")
        val headers    = (Http.succeed(Response.ok.setHeaders(Headers("KeyA", "ValueA"))) @@ middleware) header "KeyA"
        assertZIO(headers(Request()))(isNone)
      },
    ),
    suite("debug")(
      test("log status method url and time") {
        val program = runApp(app @@ debug) *> TestConsole.output
        assertZIO(program)(equalTo(Vector("200 GET /health 1000ms\n")))
      },
      test("log 404 status method url and time") {
        val program = runApp(Http.empty ++ Http.notFound @@ debug) *> TestConsole.output
        assertZIO(program)(equalTo(Vector("404 GET /health 0ms\n")))
      },
    ),
    suite("when")(
      test("condition is true") {
        val program = runApp(self.app @@ debug.when(_ => true)) *> TestConsole.output
        assertZIO(program)(equalTo(Vector("200 GET /health 1000ms\n")))
      } +
        test("condition is false") {
          val log = runApp(self.app @@ debug.when(_ => false)) *> TestConsole.output
          assertZIO(log)(equalTo(Vector()))
        },
    ),
    suite("whenZIO")(
      test("condition is true") {
        val program = runApp(self.app @@ debug.whenZIO(_ => ZIO.succeed(true))) *> TestConsole.output
        assertZIO(program)(equalTo(Vector("200 GET /health 1000ms\n")))
      },
      test("condition is false") {
        val log = runApp(self.app @@ debug.whenZIO(_ => ZIO.succeed(false))) *> TestConsole.output
        assertZIO(log)(equalTo(Vector()))
      },
    ),
    suite("race")(
      test("achieved") {
        val program = runApp(self.app @@ timeout(5 seconds)).map(_.status)
        assertZIO(program)(equalTo(Status.Ok))
      },
      test("un-achieved") {
        val program = runApp(self.app @@ timeout(500 millis)).map(_.status)
        assertZIO(program)(equalTo(Status.RequestTimeout))
      },
    ),
    suite("combine")(
      test("before and after") {
        val middleware = runBefore(Console.printLine("A"))
        val program    = runApp(self.app @@ middleware) *> TestConsole.output
        assertZIO(program)(equalTo(Vector("A\n")))
      },
      test("add headers twice") {
        val middleware = addHeader("KeyA", "ValueA") ++ addHeader("KeyB", "ValueB")
        val headers    = (Http.ok @@ middleware).headerValues
        assertZIO(headers(Request()))(contains("ValueA") && contains("ValueB"))
      },
      test("add and remove header") {
        val middleware = addHeader("KeyA", "ValueA") ++ removeHeader("KeyA")
        val program    = (Http.ok @@ middleware) header "KeyA"
        assertZIO(program(Request()))(isNone)
      },
    ),
    suite("ifRequestThenElseZIO")(
      test("if the condition is true take first") {
        val app = (Http.ok @@ ifRequestThenElseZIO(condZIO(true))(midA, midB)) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false take 2nd") {
        val app =
          (Http.ok @@ ifRequestThenElseZIO(condZIO(false))(midA, midB)) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("B")))
      },
    ),
    suite("ifRequestThenElse")(
      test("if the condition is true take first") {
        val app = Http.ok @@ ifRequestThenElse(cond(true))(midA, midB) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false take 2nd") {
        val app = Http.ok @@ ifRequestThenElse(cond(false))(midA, midB) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("B")))
      },
    ),
    suite("whenStatus")(
      test("if the condition is true apply middleware") {
        val app = Http.ok @@ Middleware.whenStatus(_ == Status.Ok)(midA) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false don't apply the middleware") {
        val app = Http.ok @@ Middleware.whenStatus(_ == Status.NoContent)(midA) header "X-Custom"
        assertZIO(app(Request()))(isNone)
      },
    ),
    suite("whenRequestZIO")(
      test("if the condition is true apply middleware") {
        val app = (Http.ok @@ whenRequestZIO(condZIO(true))(midA)) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false don't apply any middleware") {
        val app = (Http.ok @@ whenRequestZIO(condZIO(false))(midA)) header "X-Custom"
        assertZIO(app(Request()))(isNone)
      },
    ),
    suite("whenRequest")(
      test("if the condition is true apply middleware") {
        val app = Http.ok @@ Middleware.whenRequest(cond(true))(midA) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false don't apply the middleware") {
        val app = Http.ok @@ Middleware.whenRequest(cond(false))(midA) header "X-Custom"
        assertZIO(app(Request()))(isNone)
      },
    ),
    suite("whenResponseZIO")(
      test("if the condition is true apply middleware") {
        val app = (Http.ok @@ whenResponseZIO(condZIO(true))(midA)) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false don't apply any middleware") {
        val app = (Http.ok @@ whenResponseZIO(condZIO(false))(midA)) header "X-Custom"
        assertZIO(app(Request()))(isNone)
      },
    ),
    suite("whenResponse")(
      test("if the condition is true apply middleware") {
        val app = Http.ok @@ Middleware.whenResponse(cond(true))(midA) header "X-Custom"
        assertZIO(app(Request()))(isSome(equalTo("A")))
      },
      test("if the condition is false don't apply the middleware") {
        val app = Http.ok @@ Middleware.whenResponse(cond(false))(midA) header "X-Custom"
        assertZIO(app(Request()))(isNone)
      },
    ),
    suite("cookie")(
      test("addCookie") {
        val cookie = Cookie("test", "testValue")
        val app    = (Http.ok @@ addCookie(cookie)).header("set-cookie")
        assertZIO(app(Request()))(
          equalTo(cookie.encode.toOption),
        )
      },
      test("addCookieM") {
        val cookie = Cookie("test", "testValue")
        val app    =
          (Http.ok @@ addCookieZIO(ZIO.succeed(cookie))).header("set-cookie")
        assertZIO(app(Request()))(
          equalTo(cookie.encode.toOption),
        )
      },
    ),
    suite("signCookies")(
      test("should sign cookies") {
        val cookie = Cookie("key", "value").withHttpOnly(true)
        val app    = Http.ok.withSetCookie(cookie) @@ signCookies("secret") header "set-cookie"
        assertZIO(app(Request()))(equalTo(cookie.sign("secret").encode.toOption))
      } +
        test("sign cookies no cookie header") {
          val app = (Http.ok.addHeader("keyA", "ValueA") @@ signCookies("secret")).headerValues
          assertZIO(app(Request()))(contains("ValueA"))
        },
    ),
    suite("trailingSlashDrop")(
      test("should drop trailing slash") {
        val urls = Gen.fromIterable(
          Seq(
            ""        -> "",
            "/"       -> "",
            "/a"      -> "/a",
            "/a/b"    -> "/a/b",
            "/a/b/"   -> "/a/b",
            "/a/"     -> "/a",
            "/a/?a=1" -> "/a/?a=1",
            "/a?a=1"  -> "/a?a=1",
          ),
        )
        checkAll(urls) { case (url, expected) =>
          val app = Http.collect[Request] { case req => Response.text(req.url.encode) } @@ dropTrailingSlash
          for {
            url      <- ZIO.fromEither(URL.fromString(url))
            response <- app(Request(url = url))
            text     <- response.body.asString
          } yield assertTrue(text == expected)
        }
      },
    ),
    suite("trailingSlashRedirect")(
      test("should send a redirect response") {
        val urls = Gen.fromIterable(
          Seq(
            "/"     -> "",
            "/a/"   -> "/a",
            "/a/b/" -> "/a/b",
          ),
        )

        checkAll(urls zip Gen.fromIterable(Seq(true, false))) { case (url, expected, perm) =>
          val app      = Http.ok @@ redirectTrailingSlash(perm)
          val location = Some(expected)
          val status   = if (perm) Status.PermanentRedirect else Status.TemporaryRedirect

          for {
            url      <- ZIO.fromEither(URL.fromString(url))
            response <- app(Request(url = url))
          } yield assertTrue(
            response.status == status,
            response.headers.location == location,
          )
        }
      },
      test("should not send a redirect response") {
        val urls = Gen.fromIterable(
          Seq(
            "",
            "/a",
            "/a/b",
            "/a/b/?a=1",
          ),
        )

        checkAll(urls) { url =>
          val app = Http.ok @@ redirectTrailingSlash(true)
          for {
            url      <- ZIO.fromEither(URL.fromString(url))
            response <- app(Request(url = url))
          } yield assertTrue(response.status == Status.Ok)
        }
      },
    ),
  )

  private def cond(flg: Boolean) = (_: Any) => flg

  private def condZIO(flg: Boolean) = (_: Any) => ZIO.succeed(flg)

  private def runApp[R, E](app: HttpApp[R, E]): ZIO[R, Option[E], Response] = {
    for {
      fib <- app { Request(url = URL(!! / "health")) }.fork
      _   <- TestClock.adjust(10 seconds)
      res <- fib.join
    } yield res
  }
}

package zio.http

import zio._
import zio.http.model.{Method, Status}
import zio.test._

object TestClientSpec extends ZIOSpecDefault {
  def spec = suite("TestClient")(
    suite("Stateful")(
      test("happy path")(
        for {
          testClient <- ZIO
            .service[TestClient[Int]]
            .map(_.addHandlerState { case (state, _: Request) =>
              if (state == 0)
                (state + 1, Response.ok)
              else
                (state + 1, Response.status(Status.InternalServerError))
            })
          response1  <- testClient.request(Request.get(URL.root))
          response2  <- testClient.request(Request.get(URL.root))
        } yield assertTrue(response1.status == Status.Ok) && assertTrue(response2.status == Status.InternalServerError),
      ),
    ).provideSome[Scope](
      ZLayer.fromZIO(TestClient.make(0)),
    ),
    suite("Stateless")(
      test("happy path")(
        for {
          testClient <- ZIO
            .service[TestClient[Unit]]
            .map(_.addHandler { case (request) =>
              if (request.method == Method.GET)
                Response.ok
              else
                Response.status(Status.InternalServerError)
            })
          response1  <- testClient.request(Request.get(URL.root))
          response2  <- testClient.request(Request.post(Body.empty, URL.root))
        } yield assertTrue(response1.status == Status.Ok) && assertTrue(response2.status == Status.InternalServerError),
      ),
    ).provideSome[Scope](
      ZLayer.fromZIO(TestClient.make),
    ),
  )

}

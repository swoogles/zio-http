package zio.http

import zio.test.{ZIOSpecDefault, assertCompletes}
import zio._
import zio.http.model.Status

object TestClientSpec extends ZIOSpecDefault {
  def spec = suite("TestClient")(
    suite("Stateful")(
      test("happy path")(
        for {
          _ <- ZIO.serviceWith[TestClient[Int]](testClient => testClient.addHandlerState{
            case (state, _: Request) =>
              if (state == 0)
                (state +1, Response.ok)
              else
                (state +1, Response.status(Status.InternalServerError))
          }.request(Request.get(URL.root)))
        } yield assertCompletes
      )
    ).provideSome[Scope](
      ZLayer.fromZIO(TestClient.make(2))
    )
  )


}

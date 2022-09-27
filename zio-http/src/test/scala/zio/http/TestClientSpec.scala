package zio.http

import zio._
import zio.http.URL.Location
import zio.http.model.Scheme
import zio.test._

object TestClientSpec extends ZIOSpecDefault {
  def spec = suite("TestClientSpec")(
    test("test") {
      for {
        _             <- ZIO
          .serviceWithZIO[Client](
            _.request(Request(url = URL(Path.root / "foo", Location.Absolute(Scheme.HTTP, "localhost", port = 8080)))),
          )
          .ignore
        middleTraffic <- TestClient.interactions().debug
        _             <- ZIO
          .serviceWithZIO[Client](
            _.request(Request(url = URL(Path.root / "users", Location.Absolute(Scheme.HTTP, "localhost", port = 8080)))),
          )
          .ignore
        finalTraffic  <- TestClient.interactions().debug
      } yield assertTrue(middleTraffic.length == 1) && assertTrue(finalTraffic.length == 2)
    },
  ).provideSome[Scope](TestClient.make)

}

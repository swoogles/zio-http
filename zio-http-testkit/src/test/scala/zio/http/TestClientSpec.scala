package zio.http

import zio._
import zio.test._

object TestClientSpec extends ZIOSpecDefault {
  def spec = suite("TestClient")(
    test("Happy Path")(
      for {
        initialErrorResponse <- Client.request(Request.get(URL.root)).flip
        _               <- TestClient.addHandler(request => ZIO.succeed(Response.ok))
        alteredResponse <- Client.request(Request.get(URL.root))
      } yield assertTrue(initialErrorResponse.getMessage.contains("Unhandled request:")),
    ),
  ).provide(TestClient.layer)

}

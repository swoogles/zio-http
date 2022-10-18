package zio.http

import zio._
import zio.http.model.Status
import zio.test._

object TestClientSpec extends ZIOSpecDefault {
  def spec =
    suite("TestClient")(
      suite("Happy Paths")(
        test("addRequestResponse"){
          val request = Request.get(URL.root)
          for {
            _               <- TestClient.addRequestResponse(request, Response.ok)
            goodResponse <- Client.request(request)
            badResponse <- Client.request(Request.get(URL(Path.decode("users"))))
          } yield assertTrue(goodResponse.status == Status.Ok) && assertTrue(badResponse.status == Status.NotFound)
        },
//        test("addHandler")(
//          for {
//            _               <- TestClient.addHandler(request => ZIO.succeed(Response.ok))
//            alteredResponse <- Client.request(Request.get(URL.root))
//          } yield assertTrue(true),
//        ),
      ),
//      suite("sad paths")(
//        test("error when submitting a request to a blank TestServer")(
//          for {
//            initialErrorResponse <- Client.request(Request.get(URL.root)).flip
//          } yield assertTrue(initialErrorResponse.getMessage.contains("Unhandled request:")),
//        )
//      )
    ).provide(TestClient.layer)

}

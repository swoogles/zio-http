package zio.http

import zio._
import zio.http.model.Status
import zio.test._

object DownstreamTestClientSpec extends ZIOSpecDefault{
  sealed trait ServiceStatus
  case object Good extends ServiceStatus
  case object Bad extends ServiceStatus

  case class ServiceX(client: Client) {
    val logic: ZIO[Any, Throwable, ServiceStatus] =
      for {
        response <- client.request(Request.get(URL.root))
      } yield
        if (response.status == Status.Ok)
          Good
        else
          Bad

  }

  object ServiceX {
    val layer: ZLayer[Client, Nothing, ServiceX] =
      ZLayer.fromFunction(ServiceX(_))
  }

  def spec = test("blah")(
    for {
      _ <- ZIO.debug("TODO")
    } yield assertCompletes

  )


}

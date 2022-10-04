package zio.http
import zio.http.model.{Headers, Method, Scheme, Version}
import zio.http.socket.SocketApp
import zio.{Ref, Scope, Trace, ZIO}

case class Blah(x: Int) {
  copy(x = 3)
}

final case class TestClient[State](
                              state: Ref[State],
                              behavior: PartialFunction[(State, Request), (State, Response)],
                            ) extends Client {

  /**
   * Define stateful behavior for Client
   * @param pf
   *   Stateful behavior
   * @return
   *   The TestClient with new behavior.
   *
   * @example
   *   {{{
   * ZIO.serviceWithZIO[TestClient[Int]](_.addHandlerState { case (state, _: Request) =>
   *   if (state > 0)
   *     (state + 1, Response(Status.InternalServerError))
   *   else
   *     (state + 1, Response(Status.Ok))
   * }
   *   }}}
   */
  def addHandlerState(
                       pf: PartialFunction[(State, Request), (State, Response)],
                     ): TestClient[State] =
  // Can't call copy here, because it's a final def inside ZClient
    new TestClient[State](
      state, behavior.orElse(pf)
    )

  override def headers: Headers =
    Headers.empty

  override def hostOption: Option[String] = None

  override def pathPrefix: Path = Path.empty

  override def portOption: Option[Int] = None

  override def queries: QueryParams = QueryParams.empty

  override def schemeOption: Option[Scheme] = None

  override def sslConfig: Option[ClientSSLConfig] = None

  override protected def requestInternal(
    body: Body,
    headers: Headers,
    hostOption: Option[String],
    method: Method,
    pathPrefix: Path,
    portOption: Option[Int],
    queries: QueryParams,
    schemeOption: Option[Scheme],
    sslConfig: Option[ClientSSLConfig],
    version: Version,
  )(implicit trace: Trace): ZIO[Any, Throwable, Response] = {
    val reconstructedRequest = Request(body, headers, method, URL(pathPrefix), version, remoteAddress = None)

     state.modify(state1 => behavior((state1, reconstructedRequest)).swap)

  }

  override protected def socketInternal[Env1 <: Any](
    app: SocketApp[Env1],
    headers: Headers,
    hostOption: Option[String],
    pathPrefix: Path,
    portOption: Option[Int],
    queries: QueryParams,
    schemeOption: Option[Scheme],
    version: Version,
  )(implicit trace: Trace): ZIO[Env1 with Scope, Throwable, Response] = throw new RuntimeException("8")

}

object TestClient {
  def make[State](initial: State): ZIO[Scope, Throwable, TestClient[State]] =
    for {
      state  <- Ref.make(initial)
    } yield TestClient(state, empty)

  private def empty[State]: PartialFunction[(State, Request), (State, Response)] = {
    case _ if false => throw new RuntimeException("boom")
  }
}
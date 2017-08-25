import akka.actor.ActorSystem
import akka.util.ByteString
import colossus.IOSystem
import colossus.metrics.logging.ColossusLogging
import colossus.protocols.http.Http
import colossus.protocols.http.HttpMethod._
import colossus.protocols.http.UrlParsing._
import colossus.protocols.http.{HttpServer, Initializer, RequestHandler}
import colossus.protocols.redis.Redis
import colossus.service.GenRequestHandler.PartialHandler
import colossus.service.{Callback, HostManager}


object RedisClient extends App with ColossusLogging {

  implicit val actorSystem = ActorSystem()
  implicit val ioSystem    = IOSystem()
  implicit val ec          = actorSystem.dispatcher


  // #redis-client
  HttpServer.start("example-server", 9000) { initContext =>
    new Initializer(initContext) {

      val hostManager = new HostManager()
      hostManager.addHost("localhost", 6379)
      hostManager.addHost("localhost", 6379)
      hostManager.addHost("localhost", 6379)
      hostManager.addHost("localhost", 6379)

      val redisClient = Redis.futureClient(hostManager)

      override def onConnect = serverContext => new RequestHandler(serverContext) {
        override def handle: PartialHandler[Http] = {

          case req @ Get on Root / "get" / key => {
            val f = redisClient.get(ByteString(key)).map {
              case data =>
                //throw new Exception("No")
                req.ok(data.utf8String)
              case _    => req.notFound(s"Key $key was not found")
            }

            Callback.fromFuture(f)
          }

          case req @ Get on Root / "set" / key / value => {
            val f = redisClient.set(ByteString(key), ByteString(value)).map { _ =>
              req.ok("OK")
            }

            Callback.fromFuture(f)
          }
        }
      }
    }
  }
  // #redis-client

}

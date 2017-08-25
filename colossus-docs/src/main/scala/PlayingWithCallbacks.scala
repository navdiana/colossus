import akka.actor.{Actor, ActorSystem, Props}
import colossus.metrics.logging.ColossusLogging
import colossus.service.{Callback, CallbackExec, CallbackExecutor}

import scala.concurrent.Future
import scala.util.{Success, Try}
import scala.concurrent.duration._

object PlayingWithCallbacks extends App with ColossusLogging {

  import scala.concurrent.ExecutionContext.Implicits.global
  val actorSystem = ActorSystem("here")

  val func = { f: (Try[Int] => Unit) =>
    f(Success(5))
  }

  val someActor = actorSystem.actorOf(Props(new Actor {
    override def receive = {
      case c: CallbackExec =>
        c.in match {
          case None =>
            info(s"GOING TO EXECUTE ${c}")
            c.execute()
          case Some(wait) => {
            info(s"GOING TO DELAY ${c}")
            context.system.scheduler.scheduleOnce(c.in.get, self, CallbackExec(c.cb))
          }
        }
    }
  }))

  implicit val ce = CallbackExecutor(someActor)





  def unmappedCallback(): Unit = {
    // UnmappedCallback
    val c = Callback.apply(func)

    c.execute {
      _ => throw new Exception("exception")
    }
  }

  def constantCallback(): Unit = {
    val c = Callback.successful("HI")

    c.execute {
      _ => throw new Exception("exception")
    }
  }


  def scheduledCallback(): Unit = {
    val c = Callback.schedule(5.seconds)(Callback.apply(func))

    c.execute {
      case _ => throw new Exception("exception")
    }
  }


  constantCallback()



}

package colossus.service

import colossus.metrics.logging.ColossusLogging

import scala.concurrent.duration._
import scala.language.higherKinds

trait LBC {
  def addClient(host: String, port: Int): Unit
  def removeClient(host: String, port: Int, allInstances: Boolean): Unit
}

case class LoadBalanceClient[P <: Protocol, M[_], +T <: Sender[P, M], E](
  hostManager: HostManager,
  clientFactory: ClientFactory[P, M, T, E])(implicit env: E, async: Async[M]) extends Sender[P, M] with LBC with ColossusLogging {

  case class ClientWrapper(client: Sender[P, M], host: String, port: Int)

  var clients = Seq.empty[ClientWrapper]

  val retryPolicy = hostManager.retryPolicy

  var permutations = new PermutationGenerator(clients.map { _.client })

  // first register with the host manager, which acts as the interface between the user and this class
  hostManager.attachLoadBalancer(this)

  // TODO is this thead safe?
  def addClient(host: String, port: Int): Unit = {
    clients = ClientWrapper(clientFactory(host, port), host, port) +: clients
    regeneratePermutations()
  }

  // TODO is this thead safe?
  def removeClient(host: String, port: Int, allInstances: Boolean): Unit = {
    if (allInstances) {
      val (removedClients, keepClients) = clients.partition(clientWrapper => clientWrapper.host == host && clientWrapper.port == port)
      // disconnect the old clients
      removedClients.foreach(_.client.disconnect())

      clients = keepClients
    } else {
      ???
    }
    regeneratePermutations()
  }

  private def regeneratePermutations() {
    permutations = new PermutationGenerator(clients.map { _.client })
  }

  def send(request: P#Request): M[P#Response] = {

    retryPolicy match {
      case LBRetryPolicy(Random, backoff, attempts) => ???

      case LBRetryPolicy(WithoutReplacement, backoff, attempts) =>
        val maxTries = attempts match {
          case MaxAttempts(max) => max
          case EveryHost(upperLimit) => clients.size.min(upperLimit)
        }

        val retryList = permutations.next().take(maxTries)

        def go(next: Sender[P, M], list: List[Sender[P, M]]): M[P#Response] = {
          info(s"SENDING TO $next")
          async.recoverWith(next.send(request)) {
            case err =>
              list match {
                case head :: tail =>
                  async.delay(1.second)(go(head, tail))
                case Nil =>
                  info("FAILURE TIME!")
                  //async.failure(new SendFailedException(retryList.size, err))
                  async.failure(new SendFailedException(retryList.size, err))
              }
          }
        }
        if (retryList.isEmpty) {
          async.failure(new SendFailedException(retryList.size, new Exception("Empty client list!")))
        } else {
          go(retryList.head, retryList.tail)
        }
    }
  }

  def disconnect() = {
    clients.foreach(_.client.disconnect())
  }

}

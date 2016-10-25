package colossus.streaming

import scala.util.{Try, Success, Failure}
import colossus.service.{Callback, UnmappedCallback}

sealed trait PullAction
object PullAction {
  case object PullContinue extends PullAction
  case object PullStop extends PullAction //might not need this one
  case object Stop extends PullAction //might not need this one
  case class Wait(signal: Signal) extends PullAction
  case class Terminate(reason: Throwable) extends PullAction
}

/**
 * A Source is the read side of a pipe.  You provide a handler for when an item
 * is ready and the Source will call it.  Note that if the underlying pipe has
 * multiple items ready, onReady will only be called once.  This is so that the
 * consumer of the sink can implicitly apply backpressure by only pulling when
 * it is able to
 */
trait Source[+T] extends Transport {
  
  def pull(): PullResult[T]

  def peek: PullResult[T]

  def canPullNonEmpty = peek match {
    case PullResult.Item(_) => true
    case _ => false
  }

  def outputState: TransportState


  def pull(whenReady: Try[Option[T]] => Unit): Unit = pull() match {
    case PullResult.Item(item)      => whenReady(Success(Some(item)))
    case PullResult.Error(err)      => whenReady(Failure(err))
    case PullResult.Closed          => whenReady(Success(None))
    case PullResult.Empty(signal)   => signal.notify(pull(whenReady))  
  }


  def pullWhile(fn: T => PullAction, onComplete: TerminalPullResult => Any) {
    import PullAction._
    var continue = true
    while (continue) {
      continue = peek match {
        case PullResult.Empty(trig) => {
          trig.notify(pullWhile(fn, onComplete))
          false
        }
        case p @ PullResult.Item(i) => fn(i) match {
          case PullContinue => {
            pull()
            true
          }
          case PullStop => {
            pull()
            false
          }
          case Stop => {
            false
          }
          case Wait(signal) => {
            signal.notify(pullWhile(fn, onComplete))
            false
          }
          case Terminate(reason) => {
            terminate(reason)
            onComplete(PullResult.Error(reason))
            false
          }
        }
        case PullResult.Closed => {
          onComplete(PullResult.Closed)
          false
        }
        case PullResult.Error(err) => {
          onComplete(PullResult.Error(err))
          false
        }
      }
    }
  }

  /**
   * Pull until either the supplied function returns false or there are no more
   * items immediately available to pull, in which case a `Some[NullPullResult]`
   * is returned indicating why the loop stopped.
   */
  def pullUntilNull(fn: T => Boolean): Option[NullPullResult] = {
    var done: Option[NullPullResult] = None
    while (pull() match {
      case PullResult.Item(item) => fn(item)
      case other => {
        done = Some(other.asInstanceOf[NullPullResult])
        false
      }
    }) {}
    done
  }

  def pullCB(): Callback[Option[T]] = UnmappedCallback(pull)

  def fold[U](init: U)(cb: (T, U) => U): Callback[U] = {
    pullCB().flatMap{
      case Some(i) => fold(cb(i, init))(cb)
      case None => Callback.successful(init)
    }
  }

  def foldWhile[U](init: U)(cb:  (T, U) => U)(f : U => Boolean) : Callback[U] = {
    pullCB().flatMap {
      case Some(i) => {
        val aggr = cb(i, init)
        if(f(aggr)){
          foldWhile(aggr)(cb)(f)
        }else{
          Callback.successful(aggr)
        }
      }
      case None => Callback.successful(init)
    }
  }

  def reduce[U >: T](reducer: (U, U) => U): Callback[U] = pullCB().flatMap {
    case Some(i) => fold[U](i)(reducer)
    case None => Callback.failed(new PipeStateException("Empty reduce on pipe"))
  }
    

  def ++[U >: T](next: Source[U]): Source[U] = new DualSource(this, next)

  def collected: Callback[Iterator[T]] = fold(new collection.mutable.ArrayBuffer[T]){ case (next, buf) => buf append next ; buf } map {_.toIterator}

  /**
   * Link this source to a sink.  Items will be pulled from the source and
   * pushed to the sink, respecting backpressure, until either the source is
   * closed or an error occurs.  The sink will be closed when this source is
   * closed.  If the sink is closed before this source, this source will be
   * terminated.  Other terminations are propagated in both directions.
   *
   * @param sink The sink to link to this source
   * @param linkClosed if true, the linked sink will be closed when this source is closed
   * @param linkTerminated if true, the linked sink will be terminated when this source is terminated
   */
  def into[U >: T] (sink: Sink[U], linkClosed: Boolean, linkTerminated: Boolean)(onComplete: NonOpenTransportState => Any) {
    pullWhile (
      i => sink.push(i) match {
        case PushResult.Full(signal)        => PullAction.Wait(signal)
        case PushResult.Ok                  => PullAction.PullContinue
        case PushResult.Closed              => PullAction.Terminate(new PipeStateException("Downstream link unexpectedly closed"))
        case PushResult.Error(reason)       => PullAction.Terminate(reason)
      }, {
        case PullResult.Closed => {
          if (linkClosed) {
            sink.complete()
          }
          onComplete(TransportState.Closed)
          PullAction.Stop
        }
        case PullResult.Error(err) => {
          if (linkTerminated) sink.terminate(err)
          onComplete(TransportState.Terminated(err))
          PullAction.Stop
        }
      }
    )
  }


  def into[U >: T] (sink: Sink[U]) {
    into(sink, true, true)( _ => ())
  }

}


object Source {

  def one[T](data: T) = new Source[T] {
    var item: PullResult[T] = PullResult.Item(data)
    def pull(): PullResult[T] = {
      val t = item
      item match {
        case PullResult.Error(_) => {}
        case _ => item = PullResult.Closed
      }
      t
    }

    def peek = item 

    def terminate(reason: Throwable) {
      item = PullResult.Error(reason)
    }
 
    def outputState = item match {
      case PullResult.Error(err) => TransportState.Terminated(err)
      case PullResult.Closed => TransportState.Closed
      case _ => TransportState.Open
    }
  }

  def fromArray[T](arr: Array[T]): Source[T] = fromIterator(new Iterator[T] {
    private var index = 0
    def hasNext = index < arr.length
    def next = {
      index += 1
      arr(index - 1)
    }
  })

  def fromIterator[T](iterator: Iterator[T]): Source[T] = new Source[T] {
    //this will either be set to a Left (terminate was called) or a Right(complete was called)
    private var stop : Option[Throwable] = None
    private var nextitem: NEPullResult[T] = if (iterator.hasNext) PullResult.Item(iterator.next) else PullResult.Closed

    def pull(): PullResult[T] = {
      nextitem match {
        case i @ PullResult.Item(_) => {
          val n = nextitem
          nextitem = if (iterator.hasNext) PullResult.Item(iterator.next) else PullResult.Closed
          n
        }
        case other => other
      }
    }

    def terminate(reason: Throwable) {
      nextitem = PullResult.Error(reason)
    }

    def peek = nextitem

    def outputState = nextitem match {
      case PullResult.Error(err) => TransportState.Terminated(err)
      case PullResult.Closed =>  TransportState.Closed
      case _ => TransportState.Open
    }

  }

  def empty[T] = new Source[T] {
    def pull() = PullResult.Closed 
    def peek = PullResult.Closed
    def outputState = TransportState.Closed
    def terminate(reason: Throwable){}
  }


  /**
   * Flatten a source of sources into a single source.  Sources pulled out of
   * the base source will be streamed into the flattened source one at a time.
   * Terminations are cascaded across all sources
   */
  def flatten[A](source: Source[Source[A]]): Source[A] = {
    val flattened = new BufferedPipe[A](1) //do we need to make this configurable?  probably not
    def killall(reason: Throwable) {
      flattened.terminate(reason)
      source.pullWhile(
        sub => {
          sub.terminate(reason)
          PullAction.PullContinue
        },
         _ => ()
      )
      source.terminate(reason)
    }
    def next(): Unit = source.pullWhile(
      subsource => {
        //this is a little weird, but eliminates recursion that could occur when
        //a bunch of sources are all pushed at once and all are able to complete
        //immediately.  We only want to do the recursive call of next() if the
        //sub-source does not immediately drain in this function call.  But if
        //the source is already closed by the very next line, then we know we're
        //ready for the next sub-source and can continue this pullWhile loop
        //println("pulled item")
        var callNext = false
        var closed = false
        subsource.into(flattened, false, true) {
          case TransportState.Closed => {closed = true; if (callNext) next()}
          case TransportState.Terminated(err) => killall(err)
        }

        //we have to use this variable and not just check the state of the
        //subsource mainly due to the logic in Source.into that can result in a
        //"hanging item", where the source is closed but there is still one item
        //that is sitting in a callback function waiting to be pushed.  So we
        //instead need to rely on the callback passed to into, whch will be
        //triggered once that last item has been successfully pushed
        //TODO - this weirdness can be fixed if we can figure out a way to avoid these hanging items
        if (!closed) {
          callNext = true
          PullAction.PullStop
        } else {
          PullAction.PullContinue
        }
      },
      {
        case PullResult.Closed => {
          flattened.complete()
        }
        case PullResult.Error(err) => {
          killall(err)
        }
      }
    )
    next()
    flattened
  }
}


/**
 * Wraps 2 sinks and will automatically begin reading from the second only when
 * the first is empty.  The `None` from the first sink is never exposed.  The
 * first error reported from either sink is propagated.
 */
class DualSource[T](a: Source[T], b: Source[T]) extends Source[T] {
  private var a_empty = false
  def pull(): PullResult[T] = {
    if (a_empty) {
      b.pull()
    } else {
      val r = a.pull()
      r match {
        case PullResult.Closed => {
          a_empty = true
          b.pull()
        }
        case other => r
      }
    }
  }

  def peek = a.peek match {
    case PullResult.Closed => b.peek
    case other => other
  }

  def terminate(reason: Throwable) {
    a.terminate(reason)
    b.terminate(reason)
  }

  def outputState = if (a_empty) b.outputState else a.outputState

  override def toString = s"DualSource($a,$b)"
}

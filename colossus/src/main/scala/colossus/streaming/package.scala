package colossus

import scala.language.higherKinds

package object streaming {

  trait Functor[F[_]] {
    def map[A,B](a: F[A], f: A => B): F[B]
  }

  implicit class FunctorOps[F[_], A](val value: F[A]) extends AnyVal {
    def map[B](f: A => B)(implicit fct: Functor[F]): F[B] = fct.map(value, f)
  }

  implicit object SourceMapper extends Functor[Source]{
    def map[A,B](source: Source[A], fn: A => B): Source[B] = new Source[B] {
      def pull(): PullResult[B] = source.pull().map(fn)
      def peek = source.peek.map(fn)

      def outputState = source.outputState
      def terminate(err: Throwable) {
        source.terminate(err)
      }
      override def pullWhile(whilefn: B => PullAction, onc: TerminalPullResult => Any) {
        source.pullWhile(x => whilefn(fn(x)), onc)
      }
    }

  }

  //note - sadly trying to unify this with a HKT like Functor doesn't seem to
  //work since type inferrence fails on the type-lambda needed to squash
  //Pipe[_,_] down to M[_].  See: https://issues.scala-lang.org/browse/SI-6895
  
  implicit class PipeOps[A,B](val pipe: Pipe[A,B]) extends AnyVal {
    def map[C](fn: B => C): Pipe[A,C] = {
      val mappedsource = SourceMapper.map(pipe, fn)
      new Channel(pipe, mappedsource)
    }
  }

}

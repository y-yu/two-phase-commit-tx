package tx

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import scalaprops._
import scalaz.{Equal, Monad}
import scalaz.std.anyVal._
import scala.concurrent.ExecutionContext.Implicits.global

object TransactionTest extends Scalaprops {
  implicit val txMonadInstance: Monad[Transaction] = new Monad[Transaction] {
    override def bind[A, B](fa: Transaction[A])(f: (A) => Transaction[B]): Transaction[B] =
      fa.flatMap(f)

    override def point[A](a: => A): Transaction[A] =
      Constant(a)
  }

  implicit def equalThrowable: Equal[Throwable] =
    Equal.equal( (a, b) =>
      a.equals(b)
    )

  implicit def equalFuture[A](implicit A: Equal[A], E: Equal[Throwable]): Equal[Future[A]] =
    Equal.equal( (a, b) => (
      Try(Await.result(a, Duration(5, TimeUnit.SECONDS))),
      Try(Await.result(b, Duration(5, TimeUnit.SECONDS)))
    ) match {
      case (Success(va), Success(vb)) => A.equal(va, vb)
      case (Failure(ea), Failure(eb)) => E.equal(ea, eb)
      case _ => false
    })

  implicit def equalTransaction[A](implicit A: Equal[Future[A]]): Equal[Transaction[A]] =
    A.contramap(t => t.run())

  implicit def genTransaction[A](implicit A: Gen[A]): Gen[Transaction[A]] =
    A.map(a => Constant(a))

  val transactionTest = Properties.list(
    scalazlaws.monad.all[Transaction]
  )
}

package tx

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/**
  * @note https://gist.github.com/johnynek/647372
  */
trait Transaction[+A] { self =>
  def query()(implicit ec: ExecutionContext): Future[Either[Transaction[A], Prepared[A]]]

  def map[B](f: A => B): Transaction[B] = flatMap(a => Constant(f(a)))

  def flatMap[B](f: A => Transaction[B]): Transaction[B] =
    new Transaction[B] {
      override def query()(implicit ec: ExecutionContext): Future[Either[Transaction[B], Prepared[B]]] =
        self.query() flatMap {
          case Left(trans) =>
            Future.successful(Left(trans.flatMap(f)))
          case Right(iperp) =>
            val next: Transaction[B] = f(iperp.init)
            next.query().flatMap {
              case Left(_) =>
                iperp.rollback().map(t => Left(t.flatMap(f)))
              case Right(rprep) =>
                Future.successful(
                  Right(
                    new Prepared[B] {
                      def init: B = rprep.init
                      def commit()(implicit ec: ExecutionContext): Future[Committed[B]] =
                        for {
                          _ <- iperp.commit()
                          r <- rprep.commit()
                        } yield r
                      def rollback()(implicit ec: ExecutionContext): Future[Transaction[B]] =
                        for {
                          _ <- rprep.rollback()
                          i <- iperp.rollback().map(_.flatMap(f))
                        } yield i
                    }
                  )
                )
            } recoverWith {
              case NonFatal(e) =>
                iperp.rollback().map(i => Left(i.flatMap(f)))
            }
        }
    }

  final def run()(implicit ec: ExecutionContext): Future[A] =
    query() flatMap {
      case Left(trans) => trans.run()
      case Right(prep) => prep.commit().map(_.get)
    }
}

case class Constant[+A](get: A) extends Transaction[A] { self =>
  def query()(implicit ec: ExecutionContext): Future[Either[Transaction[A], Prepared[A]]] =
    Future.successful(
      Right(
        new Prepared[A] {
          def init: A = get
          def commit()(implicit ec: ExecutionContext): Future[Committed[A]] =
            Future.successful(Committed(get))
          def rollback()(implicit ec: ExecutionContext): Future[Transaction[A]] =
            Future.successful(self)
        }
      )
    )
}

case class Committed[+A](get: A)

trait Prepared[+A] {
  def init: A
  def commit()(implicit ec: ExecutionContext): Future[Committed[A]]
  def rollback()(implicit ec: ExecutionContext): Future[Transaction[A]]
}
package net.virtualvoid.futuretests

import scala.concurrent.{ Promise, Future }
import scala.util._

object Scenarios extends App {
  import scala.concurrent.ExecutionContext.Implicits.global
  def futureApply = Future[Int](throw new RuntimeException("test"))
  def futureApplyMap = Future[Int](throw new RuntimeException("test")).map(_ + 38)
  def futureApplyMapFails = Future[Int](42).map(_ ⇒ throw new RuntimeException)

  def promiseCompletedWithFailure: Future[Int] = {
    val promise = Promise[Int]
    val f = promise.future

    promise.complete(Failure(new RuntimeException))
    f.map(_ ⇒ throw new RuntimeException)
  }
  def promiseCompletedMapFails: Future[Int] = {
    val promise = Promise[Int]
    val f = promise.future

    promise.complete(Success(42))
    f.map(_ ⇒ throw new RuntimeException)
  }

  def mapToFails: Future[String] = {
    val f = Future(42)
    f.mapTo[String]
  }

  import akka.actor.ActorSystem
  import akka.util.Timeout
  def actorAskSucceedsMapFails(implicit system: ActorSystem): Future[Int] = {
    import scala.concurrent.duration._
    import akka.actor._
    import akka.pattern.ask

    val A = system.actorOf(Props(new Actor {
      def receive = {
        case "get-int" ⇒ sender ! 35
      }
    }))

    implicit val timeout = Timeout(500.millis)
    (A ? "get-int")
      .map(_ ⇒ throw new RuntimeException)
  }
  def actorAskFails(implicit system: ActorSystem): Future[Int] = {
    import scala.concurrent.duration._
    import akka.actor._
    import akka.pattern.ask

    val A = system.actorOf(Props(new Actor {
      def receive = {
        case "get-int" ⇒ sender ! Status.Failure(new RuntimeException("Couldn't get value"))
      }
    }))

    implicit val timeout = Timeout(500.millis)
    (A ? "get-int")
      .mapTo[Int]
      .map(_ + 1)
  }
  def actorAskTimesOut(implicit system: ActorSystem): Future[Int] = {
    import scala.concurrent.duration._
    import akka.actor._
    import akka.pattern.ask

    val A = system.actorOf(Props(new Actor {
      def receive = {
        case "get-int" ⇒
        // never answer
      }
    }))

    implicit val timeout = Timeout(500.millis)
    (A ? "get-int")
      .mapTo[Int]
      .map(_ + 1)
  }

  def flatMapReceiverFails: Future[Int] = {
    val f: Future[Int] = Future(throw new RuntimeException)
    val g: Future[Int] = Future(12)

    f.flatMap { fv ⇒
      g.map { gv ⇒
        fv + gv
      }
    }
  }
  def flatMapItselfFails: Future[Int] = {
    val f: Future[Int] = Future(42)
    val g: Future[Int] = Future(12)

    f.flatMap { fv ⇒
      throw new RuntimeException
    }
  }
  def flatMapInnerMappedFutureFails: Future[Int] = {
    val f: Future[Int] = Future(12)
    val g: Future[Int] = Future(throw new RuntimeException)

    f.flatMap { fv ⇒
      g.map { gv ⇒
        fv + gv
      }
    }
  }
  def flatMapInnerMapFails: Future[Int] = {
    val f: Future[Int] = Future(12)
    val g: Future[Int] = Future(42)

    f.flatMap { fv ⇒
      g.map { gv ⇒
        throw new RuntimeException
      }
    }
  }

  promiseCompletedMapFails.onComplete {
    case Success(res) ⇒ println(s"The result was $res")
    case Failure(ex) ⇒
      ex.printStackTrace()
  }

  Thread.sleep(500)
}

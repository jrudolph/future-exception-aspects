package com.example

import akka.actor.ActorSystem
import akka.pattern.AskableActorRef
import akka.util.Timeout

import scala.concurrent.{ Promise, Future }
import scala.util.{ Success, Failure }

object FuturesTest1 extends App {
  import scala.concurrent.ExecutionContext.Implicits.global

  def throwFunc(i: Int): String = throw new RuntimeException()

  val f = Future[Int](12 + 6)

  def doSomethingWithFuture(f: Future[Int]): Future[String] =
    f.map(throwFunc)

  doSomethingWithFuture(f.map(_ + 28))
    .map("Result: " + _)
    .onComplete {
      case Success(res) ⇒ println(s"The result was $res")
      case Failure(ex) ⇒
        ex.printStackTrace()
    }

  Thread.sleep(500)
}

object FuturesAndActorsTest extends App {
  val system = ActorSystem()
  import system.dispatcher

  import scala.concurrent.duration._
  import akka.actor._
  import akka.pattern.ask

  val A = system.actorOf(Props(new Actor {
    def receive = {
      case "get-int" ⇒
        Thread.sleep(1000)
        sender ! 35
      //println("Ignoring get-int")
      // ignore
    }
  }))

  implicit val timeout = Timeout(500.millis)
  val f: Future[Int] =
    (A ? "get-int")
      .mapTo[Int]
      .map(i ⇒ if (i > 36) i + 42 else throw new RuntimeException)

  f.onComplete {
    case Success(res) ⇒ println(s"The result was $res")
    case Failure(ex)  ⇒ ex.printStackTrace()
  }
  f.onComplete(_ ⇒ system.shutdown())
}

object ManualPromisesTest extends App {
  import scala.concurrent.ExecutionContext.Implicits.global
  val promise = Promise[Int]

  val f = promise.future

  f
    .map(_ + 38)
    .map(_ ⇒ throw new RuntimeException)
    .onComplete {
      case Success(res) ⇒ println(s"The result was $res")
      case Failure(ex)  ⇒ ex.printStackTrace()
    }

  promise.complete(Success(12))

  Thread.sleep(1000)
}

object FlatMapTest extends App {
  import scala.concurrent.ExecutionContext.Implicits.global
  val f: Future[Int] = Future(13)
  val g: Future[Int] = Future(12)

  val fut =
    f.flatMap { fv ⇒
      ???
      g.map { gv ⇒
        fv + gv
      }
    }.map(toStringOrThrow)

  fut.onComplete {
    case Success(res) ⇒ println(s"The result was $res")
    case Failure(ex)  ⇒ ex.printStackTrace()
  }

  Thread.sleep(1000)
  def toStringOrThrow(i: Int): String = ??? //i.toString
}
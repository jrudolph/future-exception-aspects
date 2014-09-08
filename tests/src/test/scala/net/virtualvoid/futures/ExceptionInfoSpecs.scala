package net.virtualvoid.futures

import akka.actor.ActorSystem
import akka.testkit.TestKitBase
import net.virtualvoid.futuretests.Scenarios
import org.specs2.execute.PendingUntilFixed
import org.specs2.matcher.{ Matcher, FutureMatchers }
import org.specs2.mutable.Specification

import scala.concurrent.Future
import scala.util.{ Failure, Success }

class ExceptionInfoSpecs extends Specification with FutureMatchers with PendingUntilFixed with TestKitBase {

  "Exceptions" should {
    "provide rich exception info for" in {
      "Future(...)" in {
        Scenarios.futureApply must failWith(
          """An exception was thrown during Future processing:
            | -> Future(...)     @ Scenarios.scala:8
            |""".stripMargin)
      }
      "Future(...).map" in {
        "apply fails" in {
          Scenarios.futureApplyMap must failWith(
            """An exception was thrown during Future processing:
              | -> Future(...)     @ Scenarios.scala:9
              |      .map          @ Scenarios.scala:9
              |""".stripMargin)
        }
        "map fails" in {
          Scenarios.futureApplyMapFails must failWith(
            """An exception was thrown during Future processing:
              |    Future(...)     @ Scenarios.scala:10
              | ->   .map          @ Scenarios.scala:10
              |""".stripMargin)
        }
      }
      "manually created/completed promises (with p.future.map)" in {
        "promise completed with failure" in {
          Scenarios.promiseCompletedWithFailure must failWith(
            """An exception was thrown during Future processing:
              |    Promise(...)    @ Scenarios.scala:13
              | ->   .complete     @ Scenarios.scala:16
              |      .map          @ Scenarios.scala:17
              |""".stripMargin)
        }
        "promise completes but map fails" in {
          Scenarios.promiseCompletedMapFails must failWith(
            """An exception was thrown during Future processing:
              |    Promise(...)    @ Scenarios.scala:20
              |      .complete     @ <unknown position>
              | ->   .map          @ Scenarios.scala:24
              |""".stripMargin)
        }
      }
      "ActorRef.ask `(A ? msg).map`" in {
        "ask succeeds and map fails" in {
          Scenarios.actorAskSucceedsMapFails must failWith(
            """An exception was thrown during Future processing:
              |    Actor ?         @ Scenarios.scala:46
              | ->   .map          @ Scenarios.scala:47
              |""".stripMargin)
        }
        "ask fails" in {
          Scenarios.actorAskFails must failWith(
            """An exception was thrown during Future processing:
              | -> Actor ?         @ Scenarios.scala:61
              |      .mapTo[Int]   @ Scenarios.scala:62
              |      .map          @ Scenarios.scala:63
              |""".stripMargin)
        }
        "ask times out" in {
          Scenarios.actorAskTimesOut must failWith(
            """An exception was thrown during Future processing:
              | -> Actor ?         @ Scenarios.scala:78
              |      .mapTo[Int]   @ Scenarios.scala:79
              |      .map          @ Scenarios.scala:80
              |""".stripMargin)
        }
      }
      "Future(...).mapTo" in {
        "mapTo fails" in {
          Scenarios.mapToFails must failWith(
            """An exception was thrown during Future processing:
              |    Future(...)     @ Scenarios.scala:28
              | ->   .mapTo[java.lang.String] @ Scenarios.scala:29
              |""".stripMargin)
        }
      }
      "Future(...).flatMap(Future(...).map(...))" in {
        "flatmap receiver fails" in {
          Scenarios.flatMapReceiverFails must failWith(
            """An exception was thrown during Future processing:
              | -> Future(...)     @ Scenarios.scala:84
              |      .flatMap      @ Scenarios.scala:87
              |""".stripMargin)
        }
        "flatmap itself fails" in {
          Scenarios.flatMapItselfFails must failWith(
            """An exception was thrown during Future processing:
              |    Future(...)     @ Scenarios.scala:94
              | ->   .flatMap      @ Scenarios.scala:97
              |""".stripMargin)
        }
        "inner mapped Future fails" in {
          Scenarios.flatMapInnerMappedFutureFails must failWith(
            """An exception was thrown during Future processing:
              |    Future(...)     @ Scenarios.scala:102
              |      .flatMap      @ Scenarios.scala:105
              | ->     Future(...)     @ Scenarios.scala:103
              |          .map          @ Scenarios.scala:106
              |""".stripMargin)
        }
        "inner map fails" in {
          Scenarios.flatMapInnerMapFails must failWith(
            """An exception was thrown during Future processing:
              |    Future(...)     @ Scenarios.scala:112
              |      .flatMap      @ Scenarios.scala:115
              |        Future(...)     @ Scenarios.scala:113
              | ->       .map          @ Scenarios.scala:116
              |""".stripMargin)
        }
      }
    }
  }

  implicit lazy val system: ActorSystem = ActorSystem()

  def failWith(msg: String): Matcher[Future[_]] =
    be_==(msg).^^((_: Throwable).getMessage).await.^^((_: Future[_]).failed)
}

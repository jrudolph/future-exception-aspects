package net.virtualvoid.futures

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.util.Timeout
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.{ Around, Aspect }
import org.aspectj.lang.reflect.SourceLocation

import scala.concurrent.duration.Duration
import scala.concurrent._
import scala.reflect.ClassTag
import scala.util.{ Success, Failure, Try }
import scala.util.control.NonFatal

case class RichFutureException(flow: CallTree, exceptionPoint: CallInfo, original: Throwable) extends RuntimeException(original) {
  override def fillInStackTrace(): Throwable = {
    //setStackTrace(stackTrace.toArray)
    this
  }

  def appendFuture(change: CallTree ⇒ CallTree): RichFutureException =
    copy(flow = change(flow))

  override def getMessage: String = {
    def locationInfo(info: CallInfo): String = info.location.map(loc ⇒ s"${loc.getFileName}:${loc.getLine}").getOrElse("<unknown position>")
    def line(info: CallInfo): String = f"${info.indentedName}%-15s @ ${locationInfo(info)}"
    def marker(mark: Boolean): String = if (mark) " -> " else "    "
    def treeToString(tree: CallTree, indent: String = ""): String = tree match {
      case Empty                          ⇒ ""
      case Single(info, parent)           ⇒ s"${treeToString(parent, indent)}${marker(info == exceptionPoint)}$indent${line(info)}\n"
      case Merge(info, parent, subParent) ⇒ s"${treeToString(parent, indent)}${marker(info == exceptionPoint)}$indent${line(info)}\n${treeToString(subParent, indent + "    ")}"
    }

    "An exception was thrown during Future processing:\n" + treeToString(flow)
  }

  val oldStacks = original.getStackTrace
  original.setStackTrace(oldStacks.takeWhile(!_.getClassName.contains("net.virtualvoid.futures")))
}
object RichFutureException {
  /*def addElement(info: CallInfo): Throwable ⇒ Throwable = {
    case fut: RichFutureException ⇒ fut.append(info)
    case ex                       ⇒ RichFutureException(info.toStackTraceElement :: Nil, ex)
  }*/
}

sealed trait CallTree
case object Empty extends CallTree
case class Single(info: CallInfo, parent: CallTree) extends CallTree
case class Merge(info: CallInfo, parent: CallTree, subParent: CallTree) extends CallTree

case class CallInfo(name: String, location: Option[SourceLocation]) {
  /** indents the name if it starts with a dot */
  def indentedName: String = if (name.startsWith(".")) "  " + name else name
}
object CallInfo {
  def apply(name: String, location: SourceLocation): CallInfo = CallInfo(name, Some(location))
}
case class TracingFutureImpl[T](underlying: Future[T], tree: CallTree) extends Future[T] {
  def onComplete[U](f: (Try[T]) ⇒ U)(implicit executor: ExecutionContext): Unit = underlying.onComplete(f)(executor)
  def isCompleted: Boolean = underlying.isCompleted
  def value: Option[Try[T]] = underlying.value
  def result(atMost: Duration)(implicit permit: CanAwait): T = underlying.result(atMost)(permit)
  def ready(atMost: Duration)(implicit permit: CanAwait): this.type = {
    underlying.ready(atMost)(permit)
    this
  }

  def map[U](f: T ⇒ U, newInfo: CallInfo)(implicit executor: ExecutionContext): Future[U] = {
    TracingFutureImpl(
      underlying
        .transform({ t ⇒
          try f(t)
          catch {
            case NonFatal(ex) ⇒ throw RichFutureException(Single(newInfo, tree), newInfo, ex)
          }
        }, {
          case r: RichFutureException ⇒ r.appendFuture(old ⇒ Single(newInfo, old))
          case ex                     ⇒ ex
        }),
      Single(newInfo, tree))
  }
  def flatMap[U](f: T ⇒ Future[U], newInfo: CallInfo)(implicit executor: ExecutionContext): Future[U] = {
    val flatMapped =
      underlying
        .transform(identity,
          {
            case r: RichFutureException ⇒ r.appendFuture(old ⇒ Single(newInfo, old))
            case ex                     ⇒ ex
          })
        .flatMap { t ⇒
          val res = try f(t)
          catch {
            case NonFatal(ex) ⇒ throw RichFutureException(Single(newInfo, tree), newInfo, ex)
          }
          val tracing =
            res match {
              case t: TracingFutureImpl[U] ⇒ t
              case fut                     ⇒ TracingFutureImpl(fut, Empty)
            }

          tracing.transform(
            identity,
            {
              case r: RichFutureException ⇒ r.appendFuture(old ⇒ Merge(newInfo, tree, old))
              case ex                     ⇒ ex
            })
        }

    TracingFutureImpl(flatMapped, Single(newInfo, tree))
  }
}

case class TracingPromiseImpl[T](underlying: Promise[T], creationInfo: CallInfo) extends Promise[T] {
  import scala.concurrent.ExecutionContext.Implicits.global
  def future: Future[T] =
    TracingFutureImpl(underlying.future /*.transform(identity, {
      case NonFatal(ex) ⇒ throw RichFutureException(Single(creationInfo, Empty), creationInfo, ex)
    })*/ , Single(CallInfo(".complete", None), Single(creationInfo, Empty)))
  def tryComplete(result: Try[T]): Boolean = underlying.tryComplete(result)
  def isCompleted: Boolean = underlying.isCompleted
}

@Aspect()
class FutureDebuggingAspects {
  @Around("call(* scala.concurrent.Future$.apply(..)) && args(body, executor) && !within(net.virtualvoid.futures.*) ")
  def instrumentFutureApply[T](thisJoinPoint: ProceedingJoinPoint, body: Function0[T], executor: ExecutionContext): AnyRef = {
    val info = CallInfo("Future(...)", thisJoinPoint.getSourceLocation)

    val res = thisJoinPoint.proceed(Array(() ⇒ {
      try body()
      catch {
        case NonFatal(ex) ⇒ throw RichFutureException(Single(info, Empty), info, ex)
      }
    }, executor)).asInstanceOf[Future[T]]
    TracingFutureImpl(res, Single(info, Empty))
  }

  @Around("call(* scala.concurrent.Future.map(..)) && args(f, executor) && target(self) && !this(net.virtualvoid.futures.TracingFutureImpl)")
  def instrumentFutureMap[T, U](thisJoinPoint: ProceedingJoinPoint, f: T ⇒ U, executor: ExecutionContext, self: Future[T]): AnyRef = {
    val info = CallInfo(".map", thisJoinPoint.getSourceLocation)

    val tracing =
      self match {
        case t: TracingFutureImpl[T] ⇒ t
        case fut                     ⇒ TracingFutureImpl(fut, Empty)
      }

    val res = tracing.map(f, info)(executor)
    /*val res = thisJoinPoint.proceed().asInstanceOf[Future[AnyRef]]
    res.transform(identity, addElement("map", thisJoinPoint.getSourceLocation))(executor)*/
    res
  }

  @Around("call(* scala.concurrent.Future.flatMap(..)) && args(f, executor) && target(self) && !this(net.virtualvoid.futures.TracingFutureImpl)")
  def instrumentFutureFlatMap[T, U](thisJoinPoint: ProceedingJoinPoint, f: T ⇒ Future[U], executor: ExecutionContext, self: Future[T]): AnyRef = {
    val info = CallInfo(s".flatMap", thisJoinPoint.getSourceLocation)
    val tracing =
      self match {
        case t: TracingFutureImpl[T] ⇒ t
        case fut                     ⇒ TracingFutureImpl(fut, Empty)
      }

    tracing.flatMap(f, info)(executor)

    //thisJoinPoint.proceed()
    /*val info = CallInfo("Future.map", thisJoinPoint.getSourceLocation)

    val tracing =
      self match {
        case t: TracingFutureImpl[T] ⇒ t
        case fut                     ⇒ TracingFutureImpl(fut, Nil)
      }

    val res = tracing.map(f, info)(executor)
    /*val res = thisJoinPoint.proceed().asInstanceOf[Future[AnyRef]]
    res.transform(identity, addElement("map", thisJoinPoint.getSourceLocation))(executor)*/
    res*/
  }

  val toBoxed = Map[Class[_], Class[_]](
    classOf[Boolean] -> classOf[java.lang.Boolean],
    classOf[Byte] -> classOf[java.lang.Byte],
    classOf[Char] -> classOf[java.lang.Character],
    classOf[Short] -> classOf[java.lang.Short],
    classOf[Int] -> classOf[java.lang.Integer],
    classOf[Long] -> classOf[java.lang.Long],
    classOf[Float] -> classOf[java.lang.Float],
    classOf[Double] -> classOf[java.lang.Double],
    classOf[Unit] -> classOf[scala.runtime.BoxedUnit])

  @Around("call(* scala.concurrent.Future.mapTo(..)) && args(classTag) && target(self) && !this(net.virtualvoid.futures.TracingFutureImpl)")
  def instrumentFutureMapTo[T, U](thisJoinPoint: ProceedingJoinPoint, classTag: ClassTag[U], self: Future[T]): AnyRef = {
    //Thread.dumpStack()
    val info = CallInfo(s".mapTo[$classTag]", thisJoinPoint.getSourceLocation)

    val tracing =
      self match {
        case t: TracingFutureImpl[T] ⇒ t
        case fut                     ⇒ TracingFutureImpl(fut, Empty)
      }

    val boxedClass = {
      val c = classTag.runtimeClass
      if (c.isPrimitive) toBoxed(c) else c
    }
    require(boxedClass ne null)

    import scala.concurrent.ExecutionContext.Implicits.global // FIXME
    val res = tracing.map({ t ⇒
      boxedClass.cast(t).asInstanceOf[U]
    }, info)
    /*val res = thisJoinPoint.proceed().asInstanceOf[Future[AnyRef]]
    res.transform(identity, addElement("map", thisJoinPoint.getSourceLocation))(executor)*/
    res
  }

  // def ?(message: Any)(implicit timeout: Timeout): Future[Any] = ask(message)(timeout)
  @Around("call(* akka.pattern.AskableActorRef$.$qmark$extension(..)) && args(self, message, timeout)")
  def instrumentAkkaAsk[T, U](thisJoinPoint: ProceedingJoinPoint, self: ActorRef, message: AnyRef, timeout: Timeout): AnyRef = {
    val info = CallInfo("Actor ?", thisJoinPoint.getSourceLocation)
    val res = thisJoinPoint.proceed().asInstanceOf[Future[AnyRef]]
    import scala.concurrent.ExecutionContext.Implicits.global // FIXME
    TracingFutureImpl(
      res.transform(identity, {
        case NonFatal(ex) ⇒ throw RichFutureException(Single(info, Empty), info, ex)
      }), Single(info, Empty))
  }

  @Around("call(* scala.concurrent.Promise$.apply(..)) && !within(net.virtualvoid.futures.*) ")
  def instrumentPromiseApply[T](thisJoinPoint: ProceedingJoinPoint): AnyRef = {
    val info = CallInfo("Promise(...)", thisJoinPoint.getSourceLocation)

    val res = thisJoinPoint.proceed().asInstanceOf[Promise[T]]
    TracingPromiseImpl(res, info)
  }
  @Around("call(* scala.concurrent.Promise.complete(..)) && target(promise) && args(res) && !within(net.virtualvoid.futures.*) ")
  def instrumentPromiseComplete[T](thisJoinPoint: ProceedingJoinPoint, promise: Promise[T], res: Try[T]): AnyRef = {
    val info = CallInfo(".complete", thisJoinPoint.getSourceLocation)

    val originalTree = promise match {
      case TracingPromiseImpl(underlying, info) ⇒ Single(info, Empty)
      case _                                    ⇒ Empty
    }

    val newResult =
      res match {
        case Failure(rich: RichFutureException) ⇒ Failure(rich.appendFuture(Single(info, _)))
        case Failure(ex)                        ⇒ Failure(new RichFutureException(Single(info, originalTree), info, ex))
        case s: Success[T]                      ⇒ s
      }
    promise.complete(newResult)
  }
}

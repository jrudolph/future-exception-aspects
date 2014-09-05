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
import scala.util.Try
import scala.util.control.NonFatal

case class RichFutureException(flow: CallTree, exceptionPoint: CallInfo, original: Throwable) extends RuntimeException(original) {
  override def fillInStackTrace(): Throwable = {
    //setStackTrace(stackTrace.toArray)
    this
  }

  def appendFuture(change: CallTree ⇒ CallTree): RichFutureException =
    copy(flow = change(flow))

  override def getMessage: String = {
    def line(info: CallInfo): String = f"${info.indentedName}%-15s (called at ${info.location.getFileName}%s:${info.location.getLine}%d)"
    def marker(mark: Boolean): String = if (mark) " -> " else "    "
    def treeToString(tree: CallTree, indent: String = ""): String = tree match {
      case Empty                          ⇒ ""
      case Single(info, parent)           ⇒ s"${treeToString(parent, indent)}${marker(info == exceptionPoint)}$indent${line(info)}\n"
      case Merge(info, parent, subParent) ⇒ s"${treeToString(parent, indent)}${marker(info == exceptionPoint)}$indent${line(info)}\n${treeToString(subParent, indent + "  ")}"
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

case class CallInfo(name: String, location: SourceLocation) {
  def toStackTraceElement: StackTraceElement =
    new StackTraceElement("Future", name, location.getFileName, location.getLine)

  /** indents the name if it starts with a dot */
  def indentedName: String = if (name.startsWith(".")) "  " + name else name
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
    val flatMapped = underlying.flatMap { t ⇒
      val res = try f(t)
      catch {
        case NonFatal(ex) ⇒ throw RichFutureException(tree, newInfo, ex)
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
    }.transform(identity,
      {
        case r: RichFutureException ⇒ r.appendFuture(old ⇒ Single(newInfo, old))
        case ex                     ⇒ ex
      })

    TracingFutureImpl(flatMapped, Single(newInfo, tree))
  }
}

case class TracingPromiseImpl[T](underlying: Promise[T], creationInfo: CallInfo) extends Promise[T] {
  def future: Future[T] = TracingFutureImpl(underlying.future, Single(creationInfo, Empty))
  def tryComplete(result: Try[T]): Boolean = underlying.tryComplete(result)
  def isCompleted: Boolean = underlying.isCompleted
}

@Aspect()
class FutureDebuggingAspects {
  @Around("call(* scala.concurrent.Future$.apply(..)) && args(body, executor) && !within(net.virtualvoid.futures.*) ")
  def instrumentFutureApply[T](thisJoinPoint: ProceedingJoinPoint, body: Function0[T], executor: ExecutionContext): AnyRef = {
    println(s"Future.apply called at ${thisJoinPoint.getSourceLocation} with executor $executor")
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

    //Thread.dumpStack()
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
    println(s"Future.flatMap called at ${thisJoinPoint.getSourceLocation}")
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
}

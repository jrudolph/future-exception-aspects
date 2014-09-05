# Expected exceptions

## Cases:
 - Basic future uses
   - Future.failed
   - Future.successful
   - Future.apply
   - Future.onComplete
   - Future.map
   - Future.flatMap

 - Interaction with akka
   - ask-Pattern (Which source pos asked?)
 - Interaction with Try
 - Interaction with third-party code

### Basic

#### `Future.failed`

```scala
def abc = {
   Future.failed(ex)
}
```

should just return `ex`.

#### `Future.apply`

```scala
def throwFunc() = throw new RuntimeException

def abc = {
  val x = Future(throwFunc())
}
```

```
throwFunc()
abc (anon func)
<async boundary>
Future.apply
abc
<rest if runtime exceptions are wanted>
```

#### `Future.map`

Example 1:

```scala
def expensive(): Int = // ...
def throwFunc(i: Int) = throw new RuntimeException

def abc = {
  val x = Future(expensive())
  x.map(throwFunc(_))
}
```

```
throwFunc()
abc (anon map func)
<async boundary>
Future.map       <- what has called Future.map ?
<async boundary> // should we show this, and if yes, why?
Future.apply
abc
<rest>
```

Example 2:

```scala
def mapper(f: Future[Int]) = {
  f.map(throwFunc(_))
}
```

Example 3:

```scala
val func = (i: Int) => throwFunc(i)

def mapped(f: Future[Int]) = {
  f.map(func)
}
```

#### `Future.flatMap`

```scala
val f: Future[Int] = ...
val g: Future[Int] = ...

f.flatMap { fv =>
  g.map { gv =>
    fv + gv
  }
}
 .map(_.toStringOrThrow)
```

Scenario 1:

g reports an error:

```
val g: Future[Int] = Future(throw ...)

   Future.apply   at `val f`
   Future.flatMap   at `f.flatMap`
->   Future.apply   at `val g`
     Future.map     at `g.map`
   Future.map       at `.map(_.toString...)`
```

f reports an error:

```
-> Future.apply   at `val f`
   Future.flatMap at `f.flatMap` // we don't know anything else about the other branch of execution as the inner code was never run
   Future.map     at `.map(toString...)`
```

Outer map reports an error:

```
  Future.apply   at `val f`
  Future.flatMap at `f.flatMap`
    Future.apply at `val g`
    Future.map   at `f.map`
  Future.map     at `.map(toString...)`
```

inner map reports an error:


### Interactions

#### Akka's ask

#### manual Promise creation

```scala

def throwFunc(i: Int) = throw new RuntimeException

def abc = {
  val x = Promise[Int]
  x.map(throwFunc(_))
}

def ghi(p: Promise[Int]) = {
  p.completeWith(12)
}
```

```
throwFunc
abc (anon map func)
<virtual stack trace following>
Future.map at abc map-invocation
Promise.complete at `ghi` complete invocation
Promise.apply at `abc` Promise creation
```
package lila.base

import alleycats.Zero
import cats.data.Validated
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import java.lang.Math.{ max, min }
import org.joda.time.{ DateTime, Duration }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext as EC, Future }
import scala.util.matching.Regex
import scala.util.Try

import cats.data.NonEmptyList
import java.util.Base64
import akka.actor.Scheduler
import lila.common.Chronometer
import scala.collection.BuildFrom

trait LilaLibraryExtensions extends LilaTypes:

  extension [A](self: A)
    def unit: Unit          = ()
    def ap[B](f: A => B): B = f(self)

  extension (self: Long)
    def atLeast(bottomValue: Long): Long       = max(self, bottomValue)
    def atMost(topValue: Long): Long           = min(self, topValue)
    def squeeze(bottom: Long, top: Long): Long = max(min(self, top), bottom)
    def toSaturatedInt: Int =
      if (self.toInt == self) self.toInt
      else if (self > 0) Integer.MAX_VALUE
      else Integer.MIN_VALUE

  extension (self: Int)
    def atLeast(bottomValue: Int): Int      = max(self, bottomValue)
    def atMost(topValue: Int): Int          = min(self, topValue)
    def squeeze(bottom: Int, top: Int): Int = max(min(self, top), bottom)

  extension (self: Float)
    def atLeast(bottomValue: Float): Float        = max(self, bottomValue)
    def atMost(topValue: Float): Float            = min(self, topValue)
    def squeeze(bottom: Float, top: Float): Float = max(min(self, top), bottom)

  extension (self: Double)
    def atLeast(bottomValue: Double): Double         = max(self, bottomValue)
    def atMost(topValue: Double): Double             = min(self, topValue)
    def squeeze(bottom: Double, top: Double): Double = max(min(self, top), bottom)

  extension [A](self: Option[A])

    def fold[X](some: A => X, none: => X): X = self.fold(none)(some)

    def orDefault(implicit z: Zero[A]): A = self getOrElse z.zero

    def toTryWith(err: => Exception): Try[A] =
      self.fold[Try[A]](scala.util.Failure(err))(scala.util.Success.apply)

    def toTry(err: => String): Try[A] = toTryWith(lila.base.LilaException(err))

    def err(message: => String): A = self.getOrElse(sys.error(message))

    def has(a: A) = self contains a

  extension (s: String)

    def replaceIf(t: Char, r: Char): String =
      if (s.indexOf(t.toInt) >= 0) s.replace(t, r) else s

    def replaceIf(t: Char, r: CharSequence): String =
      if (s.indexOf(t.toInt) >= 0) s.replace(String.valueOf(t), r) else s

    def replaceIf(t: CharSequence, r: CharSequence): String =
      if (s.contains(t)) s.replace(t, r) else s

    def replaceAllIn(regex: Regex, replacement: String) = regex.replaceAllIn(s, replacement)

  extension (config: Config)
    def millis(name: String): Int              = config.getDuration(name, TimeUnit.MILLISECONDS).toInt
    def seconds(name: String): Int             = config.getDuration(name, TimeUnit.SECONDS).toInt
    def duration(name: String): FiniteDuration = millis(name).millis

  extension (date: DateTime)
    def getSeconds: Long                   = date.getMillis / 1000
    def getCentis: Long                    = date.getMillis / 10
    def toNow                              = new Duration(date, DateTime.now)
    def atMost(other: DateTime): DateTime  = if (other isBefore date) other else date
    def atLeast(other: DateTime): DateTime = if (other isAfter date) other else date

  extension [A](v: Try[A])

    def fold[B](fe: Exception => B, fa: A => B): B =
      v match
        case scala.util.Failure(e: Exception) => fe(e)
        case scala.util.Failure(e)            => throw e
        case scala.util.Success(a)            => fa(a)

    def future: Fu[A] = fold(Future.failed, fuccess)

    def toEither: Either[Throwable, A] =
      v match
        case scala.util.Success(res) => Right(res)
        case scala.util.Failure(err) => Left(err)

  extension [A, B](v: Either[A, B])
    def orElse(other: => Either[A, B]): Either[A, B] =
      v match
        case scala.util.Right(res) => Right(res)
        case scala.util.Left(_)    => other

  extension (d: FiniteDuration)
    def toCentis = chess.Centis {
      // divide by Double, then round, to avoid rounding issues with just `/10`!
      math.round {
        if (d.unit eq MILLISECONDS) d.length / 10d
        else d.toMillis / 10d
      }
    }
    def abs = if (d.length < 0) -d else d

  extension [E, A](v: Validated[E, A]) def toFuture: Fu[A] = v.fold(err => fufail(err.toString), fuccess)

  extension [A](list: List[Try[A]]) def sequence: Try[List[A]] = Try(list map { _.get })

  extension [A](list: List[A])
    def sortLike[B](other: List[B], f: A => B): List[A] =
      list.sortWith { (x, y) =>
        other.indexOf(f(x)) < other.indexOf(f(y))
      }
    def toNel: Option[NonEmptyList[A]] =
      list match
        case Nil           => None
        case first :: rest => Some(NonEmptyList(first, rest))
    def tailOption: Option[List[A]] = list match
      case Nil       => None
      case _ :: rest => Some(rest)
    def tailSafe: List[A]         = tailOption getOrElse Nil
    def indexOption(a: A)         = Option(list indexOf a).filter(0 <= _)
    def previous(a: A): Option[A] = indexOption(a).flatMap(i => list.lift(i - 1))
    def next(a: A): Option[A]     = indexOption(a).flatMap(i => list.lift(i + 1))

  extension [A](seq: Seq[A]) def has(a: A) = seq contains a

  extension (self: Array[Byte]) def toBase64 = Base64.getEncoder.encodeToString(self)

  extension [A](fua: Fu[A])

    inline def dmap[B](f: A => B): Fu[B]       = fua.map(f)(EC.parasitic)
    inline def dforeach[B](f: A => Unit): Unit = fua.foreach(f)(EC.parasitic)

    def >>-(sideEffect: => Unit)(using ec: EC): Fu[A] =
      fua andThen { case _ =>
        sideEffect
      }

    def >>[B](fub: => Fu[B])(using ec: EC): Fu[B] =
      fua flatMap { _ =>
        fub
      }

    inline def void: Fu[Unit] =
      dmap { _ =>
        ()
      }

    inline def inject[B](b: => B): Fu[B] =
      dmap { _ =>
        b
      }

    def injectAnyway[B](b: => B)(using ec: EC): Fu[B] = fold(_ => b, _ => b)

    def effectFold(fail: Exception => Unit, succ: A => Unit)(using ec: EC): Unit =
      fua onComplete {
        case scala.util.Failure(e: Exception) => fail(e)
        case scala.util.Failure(e)            => throw e // Throwables
        case scala.util.Success(e)            => succ(e)
      }

    def fold[B](fail: Exception => B, succ: A => B)(using ec: EC): Fu[B] =
      fua map succ recover { case e: Exception => fail(e) }

    def flatFold[B](fail: Exception => Fu[B], succ: A => Fu[B])(using ec: EC): Fu[B] =
      fua flatMap succ recoverWith { case e: Exception => fail(e) }

    def logFailure(logger: => lila.log.Logger, msg: Throwable => String)(using ec: EC): Fu[A] =
      addFailureEffect { e =>
        logger.warn(msg(e), e)
      }
    def logFailure(logger: => lila.log.Logger)(using ec: EC): Fu[A] = logFailure(logger, _.toString)

    def addFailureEffect(effect: Throwable => Unit)(using ec: EC) =
      fua.failed.foreach { (e: Throwable) =>
        effect(e)
      }
      fua

    def addEffect(effect: A => Unit)(using ec: EC): Fu[A] =
      fua foreach effect
      fua

    def addEffects(fail: Exception => Unit, succ: A => Unit)(using ec: EC): Fu[A] =
      fua onComplete {
        case scala.util.Failure(e: Exception) => fail(e)
        case scala.util.Failure(e)            => throw e // Throwables
        case scala.util.Success(e)            => succ(e)
      }
      fua

    def addEffects(f: Try[A] => Unit)(using ec: EC): Fu[A] =
      fua onComplete f
      fua

    def addEffectAnyway(inAnyCase: => Unit)(using ec: EC): Fu[A] =
      fua onComplete { _ =>
        inAnyCase
      }
      fua

    def mapFailure(f: Exception => Exception)(using ec: EC): Fu[A] =
      fua recoverWith { case cause: Exception =>
        fufail(f(cause))
      }

    def prefixFailure(p: => String)(using ec: EC): Fu[A] =
      mapFailure { e =>
        LilaException(s"$p ${e.getMessage}")
      }

    def thenPp(using ec: EC): Fu[A] =
      effectFold(
        e => println("[failure] " + e),
        a => println("[success] " + a)
      )
      fua

    def thenPp(msg: String)(using ec: EC): Fu[A] =
      effectFold(
        e => println(s"[$msg] [failure] $e"),
        a => println(s"[$msg] [success] $a")
      )
      fua

    def await(duration: FiniteDuration, name: String): A =
      Chronometer.syncMon(_.blocking.time(name)) {
        try Await.result(fua, duration)
        catch
          case e: Exception =>
            lila.mon.blocking.timeout(name).increment()
            throw e
      }

    def awaitOrElse(duration: FiniteDuration, name: String, default: => A): A =
      try await(duration, name)
      catch case _: Exception => default

    def withTimeout(duration: FiniteDuration)(using ec: EC, scheduler: Scheduler): Fu[A] =
      withTimeout(duration, LilaTimeout(s"Future timed out after $duration"))

    def withTimeout(
        duration: FiniteDuration,
        error: => Throwable
    )(using ec: EC, scheduler: Scheduler): Fu[A] =
      Future firstCompletedOf Seq(
        fua,
        akka.pattern.after(duration, scheduler)(Future failed error)
      )

    def withTimeoutDefault(
        duration: FiniteDuration,
        default: => A
    )(using ec: EC, scheduler: Scheduler): Fu[A] =
      Future firstCompletedOf Seq(
        fua,
        akka.pattern.after(duration, scheduler)(Future(default))
      )

    def delay(duration: FiniteDuration)(using ec: EC, scheduler: Scheduler) =
      lila.common.Future.delay(duration)(fua)

    def chronometer    = Chronometer(fua)
    def chronometerTry = Chronometer.lapTry(fua)

    def mon(path: lila.mon.TimerPath): Fu[A]              = chronometer.mon(path).result
    def monTry(path: Try[A] => lila.mon.TimerPath): Fu[A] = chronometerTry.mon(r => path(r)(lila.mon)).result
    def monSuccess(path: lila.mon.type => Boolean => kamon.metric.Timer): Fu[A] =
      chronometerTry.mon { r =>
        path(lila.mon)(r.isSuccess)
      }.result
    def monValue(path: A => lila.mon.TimerPath): Fu[A] = chronometer.monValue(path).result

    def logTime(name: String): Fu[A]                               = chronometer pp name
    def logTimeIfGt(name: String, duration: FiniteDuration): Fu[A] = chronometer.ppIfGt(name, duration)

    def recoverDefault(using z: Zero[A], ec: EC): Fu[A] = recoverDefault(z.zero)

    def recoverDefault(default: => A)(using ec: EC): Fu[A] =
      fua recover {
        case _: LilaException                         => default
        case _: java.util.concurrent.TimeoutException => default
        case e: Exception =>
          lila.log("common").warn("Future.recoverDefault", e)
          default
      }

  extension (fua: Fu[Boolean])

    def >>&(fub: => Fu[Boolean]): Fu[Boolean] =
      fua.flatMap { if (_) fub else fuFalse }(EC.parasitic)

    def >>|(fub: => Fu[Boolean]): Fu[Boolean] =
      fua.flatMap { if (_) fuTrue else fub }(EC.parasitic)

    inline def unary_! = fua.map { !_ }(EC.parasitic)

  extension [A](fua: Fu[Option[A]])

    def orFail(msg: => String)(using ec: EC): Fu[A] =
      fua flatMap {
        _.fold[Fu[A]](fufail(msg))(fuccess)
      }

    def orFailWith(err: => Exception)(using ec: EC): Fu[A] =
      fua flatMap {
        _.fold[Fu[A]](fufail(err))(fuccess)
      }

    def orElse(other: => Fu[Option[A]])(using ec: EC): Fu[Option[A]] =
      fua flatMap {
        _.fold(other) { x =>
          fuccess(Some(x))
        }
      }

    def getOrElse(other: => Fu[A])(using ec: EC): Fu[A] = fua flatMap { _.fold(other)(fuccess) }

    def map2[B](f: A => B)(using ec: EC): Fu[Option[B]] = fua.map(_ map f)
    def dmap2[B](f: A => B): Fu[Option[B]]              = fua.map(_ map f)(EC.parasitic)

    def getIfPresent: Option[A] =
      fua.value match
        case Some(scala.util.Success(v)) => v
        case _                           => None

  extension [A, M[X] <: IterableOnce[X]](t: M[Fu[A]])
    def sequenceFu(using bf: BuildFrom[M[Fu[A]], A, M[A]], ec: EC): Fu[M[A]] = Future.sequence(t)

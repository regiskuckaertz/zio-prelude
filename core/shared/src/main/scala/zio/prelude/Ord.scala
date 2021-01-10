package zio.prelude

import zio.prelude.Equal._
import zio.prelude.coherent.{HashOrd, HashPartialOrd}
import zio.test.TestResult
import zio.test.laws.{Lawful, Laws}
import zio.{Chunk, Fiber, NonEmptyChunk}

import scala.annotation.{implicitNotFound, tailrec}
import scala.util.Try
import scala.{math => sm}

/**
 * `Ord[A]` provides implicit evidence that values of type `A` have a total
 * ordering.
 */
@implicitNotFound("No implicit Ord defined for ${A}.")
abstract class Ord[-A](implicit P: PartialOrd[A], E: Equal[A]) { self =>
  //// Exported members
  final def equal(l: A, r: A): Boolean =
    P.equal(l, r)

  final def notEqual(l: A, r: A): Boolean =
    P.notEqual(l, r)
  ////

  /**
   * Returns the result of comparing two values of type `A`.
   */
  final def compare(l: A, r: A): Ordering =
    if (Equal.refEq(l, r)) Ordering.Equals else checkCompare(l, r)

  /**
   * Returns the result of comparing two values of type `A`.
   */
  protected def checkCompare(l: A, r: A): Ordering

  final protected def checkEqual(l: A, r: A): Boolean =
    compare(l, r).isEqual

  /**
   * Constructs an `Ord[(A, B)]` given an `Ord[A]` and `Ord[B]` by first
   * comparing the `A` values, and then if the `A` values are equal comparing
   * the `B` values
   */
  final def both[B: PartialOrd: Equal](that: => Ord[B]): Ord[(A, B)] =
    bothWith(that)(identity)

  /**
   * Constructs an `Ord[C]` given an `Ord[A]`, an `Ord[B]` and a function `f`
   * to transform a `C` value into an `(A, B)`. The instance will convert each
   * `C` value into an `(A, B)`, compare the `A` values, and then if the `A`
   * values are equal compare the `B` values.
   */
  final def bothWith[B: PartialOrd: Equal, C](that: => Ord[B])(f: C => (A, B)): Ord[C] =
    Ord.make[C] { (c1, c2) =>
      (f(c1), f(c2)) match {
        case ((a1, b1), (a2, b2)) => self.compare(a1, a2) <> that.compare(b1, b2)
      }
    }(PartialOrd[(A, B)].contramap(f), Equal[(A, B)].contramap(f))

  /**
   * Constructs an `Ord[B]` given an `Ord[A]` and a function `f` to transform a
   * `B` value into an `A` value. The instance will convert each `B` value into
   * an `A` and compare the `A` values.
   */
  final def contramap[B](f: B => A): Ord[B] =
    Ord.make[B]((b1, b2) => compare(f(b1), f(b2)))(P.contramap(f), E.contramap(f))

  /**
   * Constructs an `Ord[Either[A, B]]` given an `Ord[A]` and an `Ord[B]`. If
   * one value is `Left` and one value is `Right` it will treat the `Left`
   * value as less than the `Right` value. Otherwise, it will compare the two
   * values.
   */
  final def either[B: PartialOrd: Equal](that: => Ord[B]): Ord[Either[A, B]] =
    eitherWith(that)(identity)

  /**
   * Constructs an `Ord[C]` given an `Ord[A]`, an `Ord[B]`, and a function `f`
   * to transform a `C` value into an `Either[A, B]`. The instance will convert
   * each `C` value into an `Either[A, B]`. If one value is `Left` and one
   * value is `Right` it will treat the `Left` value as less than the `Right`
   * value. Otherwise, it will compare the two values.
   */
  final def eitherWith[B: PartialOrd: Equal, C: PartialOrd: Equal](that: => Ord[B])(f: C => Either[A, B]): Ord[C] =
    Ord.make[C] { (c1, c2) =>
      (f(c1), f(c2)) match {
        case (Left(a1), Left(a2))   => self.compare(a1, a2)
        case (Left(_), Right(_))    => Ordering.LessThan
        case (Right(_), Left(_))    => Ordering.GreaterThan
        case (Right(b1), Right(b2)) => that.compare(b1, b2)
      }
    }(PartialOrd[Either[A, B]].contramap(f), Equal[Either[A, B]].contramap(f))

  /**
   * Constructs a new `Ord[A]` by mapping the result of this ordering using the
   * specified function.
   */
  final def mapOrdering(f: Ordering => Ordering): Ord[A] =
    Ord.make((l, r) => f(compare(l, r)))

  /**
   * Returns a new ordering that is the reverse of this one.
   */
  final def reverse: Ord[A] =
    mapOrdering(_.opposite)

  override def toScala[A1 <: A]: sm.Ordering[A1] =
    self.compare(_, _) match {
      case Ordering.LessThan    => -1
      case Ordering.Equals      => 0
      case Ordering.GreaterThan => 1
    }
}

object Ord extends Lawful[Ord] {

  /**
   * For all values `a1` and `a2`, `a1` is less than or equal to `a2` or `a2`
   * is less than or equal to `a1`.
   */
  val connexityLaw1: Laws[Ord] =
    new Laws.Law2[Ord]("connexityLaw1") {
      def apply[A: Ord: PartialOrd](a1: A, a2: A): TestResult =
        (a1 lessOrEqual a2) || (a2 lessOrEqual a1)
    }

  /**
   * For all values `a1` and `a2`, `a1` is greater than or equal to `a2` or
   * `a2` is greater than or equal to `a1`.
   */
  val connexityLaw2: Laws[Ord] =
    new Laws.Law2[Ord]("connexityLaw2") {
      def apply[A: Ord: PartialOrd](a1: A, a2: A): TestResult =
        (a1 greaterOrEqual a2) || (a2 greaterOrEqual a1)
    }

  /**
   * For all values `a1` and `a2`, `a1` is less than or equal to `a2` if and
   * only if `a2` is greater than or equal to `a1`.
   */
  // val complementLaw: Laws[Ord] =
  //   new Laws.Law2[Ord]("complementLaw") {
  //     def apply[A: Ord: PartialOrd](a1: A, a2: A): TestResult =
  //       (a1 lessOrEqual a2) <==> (a2 greaterOrEqual a1)
  //   }

  /**
   * The set of all laws that instances of `Ord` must satisfy.
   */
  // val laws: Laws[Ord] =
  //   connexityLaw1 +
  //     connexityLaw2 +
  //     complementLaw

  def fromScala[A: PartialOrd: Equal](implicit ordering: sm.Ordering[A]): Ord[A] =
    make((l, r) => Ordering.fromCompare(ordering.compare(l, r)))

  /**
   * The `Contravariant` instance for `Ord`.
   */
  // implicit val OrdContravariant: Contravariant[Ord] =
  //   new Contravariant[Ord] {
  //     def contramap[A, B](f: B => A): Ord[A] => Ord[B] =
  //       _.contramap(f)
  //   }

  /**
   * The `IdentityBoth` instance for `Ord`.
   */
  // implicit val OrdIdentityBoth: IdentityBoth[Ord] =
  //   new IdentityBoth[Ord] {
  //     val any: Ord[Any]                                         =
  //       AnyOrd
  //     def both[A, B](fa: => Ord[A], fb: => Ord[B]): Ord[(A, B)] =
  //       fa.both(fb)
  //   }

  /**
   * The `IdentityEither` instance for `Ord`.
   */
  // implicit val OrdIdentityEither: IdentityEither[Ord] =
  //   new IdentityEither[Ord] {
  //     def either[A, B: PartialOrd: Equal](fa: => Ord[A], fb: => Ord[B]): Ord[Either[A, B]] =
  //       fa.either(fb)
  //     val none: Ord[Nothing]                                                               =
  //       NothingOrd
  //   }

  /**
   * Summons an implicit `Ord[A]`.
   */
  def apply[A](implicit ord: Ord[A]): Ord[A] =
    ord

  /**
   * Constructs an `Ord[A]` from a function. The instance will be optimized to
   * first compare the values for reference equality and then compare the
   * values using the specified function.
   */
  def make[A: PartialOrd: Equal](ord: (A, A) => Ordering): Ord[A] = new Ord[A] {
    override protected def checkCompare(l: A, r: A): Ordering = ord(l, r)
  }

  /**
   * Constructs an `Ord[A]` from a [[scala.math.Ordering]].
   */
  def default[A: PartialOrd: Equal](implicit ord: scala.math.Ordering[A]): Ord[A] =
    make((a1, a2) => Ordering.fromCompare(ord.compare(a1, a2)))

  val AnyOrd: Ord[Any] =
    make((_: Any, _: Any) => Ordering.Equals)(PartialOrd.AnyPartialOrd, Equal.AnyEqual)

  implicit val NothingOrd: Ord[Nothing] =
    make[Nothing]((_: Nothing, _: Nothing) => sys.error("nothing.ord"))(
      PartialOrd.NothingPartialOrd,
      Equal.NothingEqual
    )

  implicit val UnitOrd: Ord[Unit] =
    make((_, _) => Ordering.Equals)

  implicit val BooleanOrd: Ord[Boolean] =
    default

  implicit val ByteOrd: Ord[Byte] =
    default

  implicit val ShortOrd: Ord[Short] =
    default

  implicit val CharOrd: Ord[Char] =
    default

  implicit val DoubleOrd: Ord[Double] =
    make((l, r) => Ordering.fromCompare(java.lang.Double.compare(l, r)))

  implicit val FloatOrd: Ord[Float] =
    make((l, r) => Ordering.fromCompare(java.lang.Float.compare(l, r)))

  implicit lazy val FiberIdOrd: Ord[Fiber.Id] =
    Ord[(Long, Long)].contramap[Fiber.Id](fid => (fid.startTimeMillis, fid.seqNumber))

  implicit val IntOrd: Ord[Int] =
    default

  implicit val LongOrd: Ord[Long] =
    default

  /**
   * Derives an `Ord[Chunk[A]]` given an `Ord[A]`.
   */
  implicit def ChunkOrd[A: Ord: PartialOrd: Equal]: Ord[Chunk[A]] =
    make(
      { (l, r) =>
        val j    = l.length
        val k    = r.length
        val OrdA = Ord[A]

        @tailrec
        def loop(i: Int): Ordering =
          if (i == j && i == k) Ordering.Equals
          else if (i == j) Ordering.LessThan
          else if (i == k) Ordering.GreaterThan
          else {
            val compare = OrdA.compare(l(i), r(i))
            if (compare.isEqual) loop(i + 1) else compare
          }

        loop(0)
      }
    )

  /**
   * Derives an `Ord[F[A]]` given a `Derive[F, Ord]` and an `Ord[A]`.
   */
  implicit def DeriveOrd[F[_], A](implicit derive: Derive[F, Ord], ord: Ord[A]): Ord[F[A]] =
    derive.derive(ord)

  /**
   * Derives an `Ord[Either[A, B]]` given an `Ord[A]` and an `Ord[B]`.
   */
  implicit def EitherOrd[A: Ord: PartialOrd: Equal, B: Ord: PartialOrd: Equal]: Ord[Either[A, B]] =
    make(
      {
        case (Left(a1), Left(a2))   => a1 =?= a2
        case (Left(_), Right(_))    => Ordering.LessThan
        case (Right(_), Left(_))    => Ordering.GreaterThan
        case (Right(b1), Right(b2)) => b1 =?= b2
      }
    )

  /**
   * Derives an `Ord[List[A]]` given an `Ord[A]`.
   */
  implicit def ListOrd[A: Ord: PartialOrd: Equal]: Ord[List[A]] = {
    val OrdA = Ord[A]

    @tailrec
    def loop(left: List[A], right: List[A]): Ordering =
      (left, right) match {
        case (Nil, Nil)           => Ordering.Equals
        case (Nil, _)             => Ordering.LessThan
        case (_, Nil)             => Ordering.GreaterThan
        case (h1 :: t1, h2 :: t2) =>
          val compare = OrdA.compare(h1, h2)
          if (compare.isEqual) loop(t1, t2) else compare
      }

    make((l, r) => loop(l, r))
  }

  /**
   * Derives an `Ord[NonEmptyChunk[A]]` given an `Ord[A]`.
   */
  implicit def NonEmptyChunkOrd[A: Ord: PartialOrd: Equal]: Ord[NonEmptyChunk[A]] =
    Ord[Chunk[A]].contramap(_.toChunk)

  /**
   * Derives an `Ord[Option[A]]` given an `Ord[A]`. `None` will be treated as
   * less than all other values.
   */
  implicit def OptionOrd[A: Ord: PartialOrd: Equal]: Ord[Option[A]] =
    Ord[Unit].eitherWith(Ord[A]) {
      case None    => Left(())
      case Some(a) => Right(a)
    }

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple2Ord[A: Ord: PartialOrd: Equal, B: Ord: PartialOrd: Equal]: Ord[(A, B)] =
    make(
      { case ((a1, b1), (a2, b2)) =>
        (a1 =?= a2) <> (b1 =?= b2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple3Ord[A: Ord: PartialOrd: Equal, B: Ord: PartialOrd: Equal, C: Ord: PartialOrd: Equal]
    : Ord[(A, B, C)] =
    make(
      { case ((a1, b1, c1), (a2, b2, c2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple4Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D)] =
    make(
      { case ((a1, b1, c1, d1), (a2, b2, c2, d2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple5Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E)] =
    make(
      { case ((a1, b1, c1, d1, e1), (a2, b2, c2, d2, e2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple6Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1), (a2, b2, c2, d2, e2, f2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple7Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1), (a2, b2, c2, d2, e2, f2, g2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple8Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1, h1), (a2, b2, c2, d2, e2, f2, g2, h2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple9Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1, h1, i1), (a2, b2, c2, d2, e2, f2, g2, h2, i2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple10Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1, h1, i1, j1), (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple11Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1), (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple12Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L)] =
    make(
      { case ((a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1), (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2)) =>
        (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple13Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple14Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple15Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple16Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple17Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple18Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal,
    R: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2) <> (r1 =?= r2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple19Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal,
    R: Ord: PartialOrd: Equal,
    S: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1, s1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2, s2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2) <> (r1 =?= r2) <> (s1 =?= s2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple20Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal,
    R: Ord: PartialOrd: Equal,
    S: Ord: PartialOrd: Equal,
    T: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1, s1, t1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2, s2, t2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2) <> (r1 =?= r2) <> (s1 =?= s2) <> (t1 =?= t2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple21Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal,
    R: Ord: PartialOrd: Equal,
    S: Ord: PartialOrd: Equal,
    T: Ord: PartialOrd: Equal,
    U: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1, s1, t1, u1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2, s2, t2, u2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2) <> (r1 =?= r2) <> (s1 =?= s2) <> (t1 =?= t2) <> (u1 =?= u2)
      }
    )

  /**
   * Derives an `Ord` for a product type given an `Ord` for each element of
   * the product type.
   */
  implicit def Tuple22Ord[
    A: Ord: PartialOrd: Equal,
    B: Ord: PartialOrd: Equal,
    C: Ord: PartialOrd: Equal,
    D: Ord: PartialOrd: Equal,
    E: Ord: PartialOrd: Equal,
    F: Ord: PartialOrd: Equal,
    G: Ord: PartialOrd: Equal,
    H: Ord: PartialOrd: Equal,
    I: Ord: PartialOrd: Equal,
    J: Ord: PartialOrd: Equal,
    K: Ord: PartialOrd: Equal,
    L: Ord: PartialOrd: Equal,
    M: Ord: PartialOrd: Equal,
    N: Ord: PartialOrd: Equal,
    O: Ord: PartialOrd: Equal,
    P: Ord: PartialOrd: Equal,
    Q: Ord: PartialOrd: Equal,
    R: Ord: PartialOrd: Equal,
    S: Ord: PartialOrd: Equal,
    T: Ord: PartialOrd: Equal,
    U: Ord: PartialOrd: Equal,
    V: Ord: PartialOrd: Equal
  ]: Ord[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
    make(
      {
        case (
              (a1, b1, c1, d1, e1, f1, g1, h1, i1, j1, k1, l1, m1, n1, o1, p1, q1, r1, s1, t1, u1, v1),
              (a2, b2, c2, d2, e2, f2, g2, h2, i2, j2, k2, l2, m2, n2, o2, p2, q2, r2, s2, t2, u2, v2)
            ) =>
          (a1 =?= a2) <> (b1 =?= b2) <> (c1 =?= c2) <> (d1 =?= d2) <> (e1 =?= e2) <> (f1 =?= f2) <> (g1 =?= g2) <> (h1 =?= h2) <> (i1 =?= i2) <> (j1 =?= j2) <> (k1 =?= k2) <> (l1 =?= l2) <> (m1 =?= m2) <> (n1 =?= n2) <> (o1 =?= o2) <> (p1 =?= p2) <> (q1 =?= q2) <> (r1 =?= r2) <> (s1 =?= s2) <> (t1 =?= t2) <> (u1 =?= u2) <> (v1 =?= v2)
      }
    )

  /**
   * Derives an `Ord[Vector[A]]` given an `Ord[A]`.
   */
  implicit def VectorOrd[A: Ord: PartialOrd: Equal]: Ord[Vector[A]] =
    make(
      { (l, r) =>
        val j    = l.length
        val k    = r.length
        val OrdA = Ord[A]

        @tailrec
        def loop(i: Int): Ordering =
          if (i == j && i == k) Ordering.Equals
          else if (i == j) Ordering.LessThan
          else if (i == k) Ordering.GreaterThan
          else {
            val compare = OrdA.compare(l(i), r(i))
            if (compare.isEqual) loop(i + 1) else compare
          }

        loop(0)
      }
    )
}

trait OrdSyntax {

  /**
   * Provides infix syntax for comparing two values with a total ordering.
   */
  implicit class OrdOps[A](val l: A) {

    /**
     * Returns the result of comparing this value with the specified value.
     */
    def =?=[A1 >: A](r: A1)(implicit ord: Ord[A1]): Ordering = ord.compare(l, r)
  }
}

sealed trait Comparison extends Product with Serializable

object Comparison {

  sealed trait NotEqual extends Comparison

}

sealed trait PartialOrdering extends Product with Serializable { self =>

  /**
   * A symbolic alias for `orElse`.
   */
  final def <>(that: => PartialOrdering): PartialOrdering =
    self orElse that

  /**
   * Returns whether this `Ordering` is `Ordering.Equals`.
   */
  final def isEqual: Boolean =
    self match {
      case Ordering.Equals => true
      case _               => false
    }

  /**
   * Returns whether this `Ordering` is `Ordering.GreaterThan`.
   */
  final def isGreaterThan: Boolean =
    self match {
      case Ordering.GreaterThan => true
      case _                    => false
    }

  /**
   * Returns whether this `Ordering` is `Ordering.LessThan`.
   */
  final def isLessThan: Boolean =
    self match {
      case Ordering.LessThan => true
      case _                 => false
    }

  /**
   * Returns this ordering, but if this ordering is equal returns the
   * specified ordering.
   */
  final def orElse(that: => PartialOrdering): PartialOrdering =
    self match {
      case Ordering.Equals => that
      case ordering        => ordering
    }

  def unify(that: PartialOrdering): PartialOrdering = (self, that) match {
    case (Ordering.LessThan, Ordering.LessThan)       => Ordering.LessThan
    case (Ordering.GreaterThan, Ordering.GreaterThan) => Ordering.GreaterThan
    case (Ordering.Equals, that)                      => that
    case (self, Ordering.Equals)                      => self
    case _                                            => PartialOrdering.Incomparable
  }
}

object PartialOrdering {

  def fromTryCompare(n: Option[Int]): PartialOrdering =
    n.fold[PartialOrdering](Incomparable)(Ordering.fromCompare)

  case object Incomparable extends PartialOrdering with Comparison.NotEqual

  /**
   * `Hash` and `PartialOrd` instance for `PartialOrdering` values.
   */
  implicit val PartialOrderingHashPartialOrd: Hash[PartialOrdering] with PartialOrd[PartialOrdering] =
    HashPartialOrd.make(
      (x: PartialOrdering) => x.hashCode,
      (l: PartialOrdering, r: PartialOrdering) =>
        (l, r) match {
          case (l: Ordering, r: Ordering)   => Ordering.OrderingHashOrd.compare(l, r)
          case (Incomparable, Incomparable) => Ordering.Equals
          case _                            => Incomparable
        }
    )

  /**
   * `Idempotent`, `Identity` (and thus `Associative`) instance for `PartialOrdering` values.
   */
  implicit val PartialOrderingIdempotentIdentity: Idempotent[PartialOrdering] with Identity[PartialOrdering] =
    new Idempotent[PartialOrdering] with Identity[PartialOrdering] {
      override def combine(l: => PartialOrdering, r: => PartialOrdering): PartialOrdering = l <> r
      override def identity: PartialOrdering                                              = Ordering.Equals
    }

  /**
   * `Idempotent`, `Identity` (and thus `Associative`) instance for `PartialOrdering` values
   * that combines them for non-lexicographic purposes.
   */
  val PartialOrderingNonlexicographicCommutativeIdempotentIdentity
    : Commutative[PartialOrdering] with Idempotent[PartialOrdering] with Identity[PartialOrdering] =
    new Commutative[PartialOrdering] with Idempotent[PartialOrdering] with Identity[PartialOrdering] {
      override def combine(l: => PartialOrdering, r: => PartialOrdering): PartialOrdering = l.unify(r)
      override def identity: PartialOrdering                                              = Ordering.Equals
    }
}

/**
 * An `Ordering` is the result of comparing two values. The result may be
 * `LessThan`, `Equals`, or `GreaterThan`.
 */
sealed trait Ordering extends PartialOrdering { self =>

  /**
   * A symbolic alias for `orElse`.
   */
  final def <>(that: => Ordering): Ordering =
    self orElse that

  /**
   * Converts this `Ordering` to an ordinal representation, with `0`
   * representing `LessThan`, `1` representing `Equals` and `2` representing
   * `GreaterThan`.
   */
  final def ordinal: Int =
    self match {
      case Ordering.LessThan    => 0
      case Ordering.Equals      => 1
      case Ordering.GreaterThan => 2
    }

  /**
   * Returns this ordering, but if this ordering is equal returns the
   * specified ordering.
   */
  final def orElse(that: => Ordering): Ordering =
    self match {
      case Ordering.Equals => that
      case ordering        => ordering
    }

  /**
   * Returns the opposite of this `Ordering`, with `LessThan` converted to
   * `GreaterThan` and `GreaterThan` converted to `LessThan`.
   */
  final def opposite: Ordering =
    self match {
      case Ordering.LessThan    => Ordering.GreaterThan
      case Ordering.Equals      => Ordering.Equals
      case Ordering.GreaterThan => Ordering.LessThan
    }
}

object Ordering {
  case object LessThan    extends Ordering with Comparison.NotEqual
  case object Equals      extends Ordering with Comparison
  case object GreaterThan extends Ordering with Comparison.NotEqual

  /**
   * Converts an integer result from [[scala.math.Ordering.compare]] or
   * [[java.lang.Comparable]] to a `Compare`.
   */
  def fromCompare(n: Int): Ordering =
    if (n < 0) LessThan
    else if (n > 0) GreaterThan
    else Equals

  /**
   * `Hash` and `Ord` instance for `Ordering` values.
   */
  implicit val OrderingHashOrd: Hash[Ordering] with Ord[Ordering] =
    HashOrd.make(
      (x: Ordering) => x.hashCode,
      (l: Ordering, r: Ordering) => Ord[Int].compare(l.ordinal, r.ordinal)
    )

  /**
   * `Idempotent`, `Identity` (and thus `Associative`) instance for `Ordering` values.
   */
  implicit val OrderingIdempotentIdentity: Idempotent[Ordering] with Identity[Ordering] =
    new Idempotent[Ordering] with Identity[Ordering] {
      override def combine(l: => Ordering, r: => Ordering): Ordering = l match {
        case Ordering.Equals => r
        case l               => l
      }

      override def identity: Ordering = Ordering.Equals
    }
}

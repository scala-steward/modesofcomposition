package modesofcomposition

import cats.effect.concurrent.Ref
import scala.collection.mutable
import scala.concurrent.duration.TimeUnit

trait TestSupport {
  type F[X] = IO[X]

  val seed = new UuidSeed(Array(1, 2, 3, 4))
  val rabbitCode = "Rabbit"
  val hippoCode = "Hippo"
  val toyRabbit = Sku(rabbitCode)
  val toyHippo = Sku(hippoCode)
  val skus = Chain(toyRabbit, toyHippo)
  val initialStock = Map(
    toyRabbit -> NatInt(5),
    toyHippo -> NatInt(1))

  val customerIdStr = "12345"
  val customerId = new CustomerId(customerIdStr)

  val currMillis = System.currentTimeMillis()

  implicit val clock = TestSupport.clock[F](currMillis)


  def fromJsonBytes[T: Decoder](bytes: Array[Byte]) = {
    io.circe.parser.decode[T](new String(bytes))
  }

  def inventory[F[_]: Sync](initialStock: Map[Sku, NatInt]): TestInventory[F] =
    new TestInventory[F](initialStock)

  def clock[F[_]: Applicative](time: Long) = new Clock[F] {
    override def realTime(unit: TimeUnit): F[Long] = F.pure(time)

    override def monotonic(unit: TimeUnit): F[Long] = F.pure(time)
  }

  def orderJson(customerIdStr: String, hippoQty: Int, rabbitQty: Int) = {
    val rabbitStr = (rabbitQty > 0).valueOrZero(s"""["$rabbitCode", $rabbitQty]""")
    val hippoStr = (hippoQty > 0).valueOrZero(s"""["$hippoCode", $hippoQty]""")
    s"""{
       |"customerId": "$customerIdStr",
       |"skuQuantities": [${Seq(rabbitStr, hippoStr).mkString(", ")}]
       |}""".stripMargin
  }

}
object TestSupport extends TestSupport

case class TestSkuLookup[F[_]: Sync](skus: Map[String, Sku]) extends SkuLookup[F] {

  override def resolveSku(s: String): F[Either[String, Sku]] = F.pure(skus.get(s).toRight(s"Sku code not found: $s"))
}

case class TestCustomerLookup[F[_]](customerIds: Map[String, CustomerId]) extends CustomerLookup[F] {

  override def resolveCustomerId(customerId: String)(implicit F: Async[F]): F[Either[String, CustomerId]] =
    F.pure(customerIds.get(customerId).toRight(s"CustomerId code not found: $customerId"))
}



case class TestInventory[F[_]: Sync](stock: Map[Sku, NatInt]) extends Inventory[F] {
  val refStock = Ref.unsafe[F, Map[Sku, NatInt]](stock)

  override def inventoryTake(skuQty: SkuQuantity): F[Either[InsufficientStock, SkuQuantity]] =
    refStock.modify(stock => {
      val stockQty = stock.getOrElse(skuQty.sku, NatInt(0))
      NatInt.from(stockQty - skuQty.quantity) match {
        case Right(remaining) =>
          (stock.updated(skuQty.sku, remaining), skuQty.asRight)
        case Left(insuffcientMsg) =>
          (stock, InsufficientStock(skuQty, stockQty).asLeft)
      }})

  override def inventoryPut(skuQty: SkuQuantity): F[Unit] =
    refStock.update(_.updatedWith(skuQty.sku)(current => current |+| (skuQty.quantity: NatInt).some))

}

class TestPublish[F[_]: Sync] extends Publish[F] {
  val refMessages = Ref.unsafe[F, Map[String, Chain[Array[Byte]]]](Map.empty)

  override def publish(topic: String, msg: Array[Byte]): F[Unit] =
    refMessages.update(_.updatedWith(topic)(_ |+| Chain(msg).some))

  def getMessages(topic: String) = refMessages.get.map(_.apply(topic))
}
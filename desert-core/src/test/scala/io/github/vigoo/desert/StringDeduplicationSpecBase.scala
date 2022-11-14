package io.github.vigoo.desert

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import io.github.vigoo.desert.BinaryDeserializer.{Deser, DeserializationEnv}
import io.github.vigoo.desert.BinaryDeserializerOps._
import io.github.vigoo.desert.BinarySerializer.{Ser, SerializationEnv}
import io.github.vigoo.desert.BinarySerializerOps._
import io.github.vigoo.desert.StringDeduplicationSpecBase.{DataV1, DataV2, OuterV1, OuterV2}
import io.github.vigoo.desert.codecs._
import zio.test.Assertion._
import zio.test._

trait StringDeduplicationSpecBase extends ZIOSpecDefault with SerializationProperties {

  val s1 = "this is a test string"
  val s2 = "and another one"
  val s3 = "and another one"

  implicit val v1codec: BinaryCodec[DataV1]
  implicit val v2codec: BinaryCodec[DataV2]
  implicit val outerv1codec: BinaryCodec[OuterV1]
  implicit val outerv2codec: BinaryCodec[OuterV2]

  private val testSer: Ser[Unit] = for {
    _ <- write(DeduplicatedString(s1))
    _ <- write(DeduplicatedString(s2))
    _ <- write(DeduplicatedString(s3))
    _ <- write(DeduplicatedString(s1))
    _ <- write(DeduplicatedString(s2))
    _ <- write(DeduplicatedString(s3))
  } yield ()

  private val testDeser: Deser[List[String]] =
    for {
      a <- read[DeduplicatedString]()
      b <- read[DeduplicatedString]()
      c <- read[DeduplicatedString]()
      d <- read[DeduplicatedString]()
      e <- read[DeduplicatedString]()
      f <- read[DeduplicatedString]()
    } yield List(a, b, c, d, e, f).map(_.string)

  override def spec: Spec[TestEnvironment, Any] =
    suite("String deduplication")(
      test("reads back duplicated strings correctly") {
        val stream = new ByteArrayOutputStream()
        val output = new JavaStreamBinaryOutput(stream)
        val result = testSer
          .provideService(SerializationEnv(output, TypeRegistry.empty))
          .either
          .runResult(SerializerState.initial)
          .flatMap { _ =>
            stream.flush()
            val inStream = new ByteArrayInputStream(stream.toByteArray)
            val input    = new JavaStreamBinaryInput(inStream)
            testDeser
              .provideService(DeserializationEnv(input, TypeRegistry.empty))
              .either
              .runResult(SerializerState.initial)
          }

        assert(result)(isRight(equalTo(List(s1, s2, s3, s1, s2, s3))))
      },
      test("reduces the serialized size") {
        val stream = new ByteArrayOutputStream()
        val output = new JavaStreamBinaryOutput(stream)
        val size   = testSer
          .provideService(SerializationEnv(output, TypeRegistry.empty))
          .either
          .runResult(SerializerState.initial)
          .map { _ =>
            stream.flush()
            stream.toByteArray.length
          }

        assert(size)(isRight(isLessThan((s1.length + s2.length) * 2)))
      },
      test("default string serialization does not breaks data evolution") {
        implicit val typeRegistry: TypeRegistry = TypeRegistry.empty
        canBeSerializedAndReadBack(
          OuterV2(DataV2("hello world"), "hello world"),
          OuterV1(DataV1(), "hello world")
        )
      }
    )
}

object StringDeduplicationSpecBase {

  case class DataV1()

  @evolutionSteps(FieldAdded[String]("newField", "unknown"))
  case class DataV2(newField: String)

  case class OuterV1(data: DataV1, other: String)

  case class OuterV2(data: DataV2, other: String)
}
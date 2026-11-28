package com.ruchij.core.kv

import cats.effect.IO
import cats.{Monad, MonadThrow}
import com.ruchij.core.kv.KeySpacedKeyValueStoreSpec.{Person, PersonKey, PersonKeySpace}
import com.ruchij.core.kv.codecs.{KVDecoder, KVEncoder}
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace, KeySpacedKeyEncoder}
import com.ruchij.core.test.IOSupport.runIO
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.jdk.CollectionConverters._

class KeySpacedKeyValueStoreSpec extends AnyFlatSpec with Matchers {

  "Inserting key-values" should "encode key and value" in runIO {
    val keyValueStore = new InMemoryKeyValueStore[IO]
    val keySpacedKeyValueStore = new KeySpacedKeyValueStore(PersonKeySpace, keyValueStore)

    val key = PersonKey(1)
    val person = Person(1, "John", "Smith")

    for {
      _ <- keySpacedKeyValueStore.put(key, person)

      _ <- IO.delay {
        keyValueStore.data.keys().asScala.toList mustBe List("person::1")
        keyValueStore.data.values().asScala.toList mustBe List("1::John::Smith")
      }

      maybePerson <- keySpacedKeyValueStore.get(key)
      emptyResult <- keySpacedKeyValueStore.get(PersonKey(2))

      _ <- IO.delay {
        maybePerson mustBe Some(person)
        emptyResult mustBe None
      }

      _ <- keySpacedKeyValueStore.remove(key)

      _ <- IO.delay {
        keyValueStore.data.keys().asScala.toList mustBe List.empty
        keyValueStore.data.values().asScala.toList mustBe List.empty
      }
    } yield (): Unit
  }

}

object KeySpacedKeyValueStoreSpec {
  final case class PersonKey(id: Long) extends KVStoreKey
  final case class Person(id: Long, firstName: String, lastName: String)

  implicit case object PersonKeySpace extends KeySpace[PersonKey, Person] {
    override val name: String = "person"

    override val maybeTtl: Option[FiniteDuration] = Some(5 seconds)
  }

  object PersonKey {
    implicit def keySpacedKeyEncoder[F[_]: Monad]: KeySpacedKeyEncoder[F, PersonKey] =
      KeySpacedKeyEncoder.of(PersonKeySpace, KVEncoder[F, Long].coMap[PersonKey](_.id))
  }

  object Person {
    implicit def kvEncoder[F[_]: Monad]: KVEncoder[F, Person] =
      KVEncoder.forProduct3((person: Person) => (person.id, person.firstName, person.lastName))

    implicit def kvDecoder[F[_]: MonadThrow]: KVDecoder[F, Person] = KVDecoder.forProduct3(Person.apply)
  }
}

package com.ruchij.core.kv

import cats.effect.IO
import com.ruchij.core.kv.KeySpacedKeyValueStoreSpec.{Person, PersonKey, PersonKeySpace}
import com.ruchij.core.kv.keys.{KVStoreKey, KeySpace}
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
    }
    yield (): Unit
  }

}

object KeySpacedKeyValueStoreSpec {
  case class PersonKey(id: Long) extends KVStoreKey
  case class Person(id: Long, firstName: String, lastName: String)

  implicit case object PersonKeySpace extends KeySpace[PersonKey, Person] {
    override val name: String = "person"

    override val ttl: FiniteDuration = 5 seconds
  }
}

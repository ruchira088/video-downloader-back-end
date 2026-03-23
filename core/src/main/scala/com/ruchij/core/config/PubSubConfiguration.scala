package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.messaging.PubSub.PubSubType
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigReader
import pureconfig.generic.auto._

final case class PubSubConfiguration(
  pubSubType: PubSubType,
  kafkaConfiguration: Option[KafkaConfiguration],
  redisConfiguration: Option[RedisConfiguration],
  databaseConfiguration: Option[DatabaseConfiguration]
)

object PubSubConfiguration {
  implicit val pubSubConfigurationConfigReader: ConfigReader[PubSubConfiguration] =
    ConfigReader.fromCursor {
      _.asObjectCursor
        .flatMap { objectCursor =>
          objectCursor
            .atKey("type")
            .flatMap { configCursor =>
              ConfigReader[PubSubType].from(configCursor)
            }
            .flatMap {
              case PubSubType.Kafka =>
                objectCursor
                  .atKey("kafka-configuration")
                  .flatMap { cursor =>
                    ConfigReader[KafkaConfiguration].from(cursor)
                  }
                  .map { kafkaConfiguration =>
                    PubSubConfiguration(
                      pubSubType = PubSubType.Kafka,
                      kafkaConfiguration = Some(kafkaConfiguration),
                      redisConfiguration = None,
                      databaseConfiguration = None
                    )
                  }

              case PubSubType.Redis =>
                objectCursor
                  .atKey("redis-configuration")
                  .flatMap { cursor =>
                    ConfigReader[RedisConfiguration].from(cursor)
                  }
                  .map { redisConfiguration =>
                    PubSubConfiguration(
                      pubSubType = PubSubType.Redis,
                      kafkaConfiguration = None,
                      redisConfiguration = Some(redisConfiguration),
                      databaseConfiguration = None
                    )
                  }

              case PubSubType.Doobie =>
                objectCursor
                  .atKey("database-configuration")
                  .flatMap { cursor =>
                    ConfigReader[DatabaseConfiguration].from(cursor)
                  }
                  .map { databaseConfiguration =>
                    PubSubConfiguration(
                      pubSubType = PubSubType.Doobie,
                      kafkaConfiguration = None,
                      redisConfiguration = None,
                      databaseConfiguration = Some(databaseConfiguration)
                    )
                  }
            }
        }
    }
}

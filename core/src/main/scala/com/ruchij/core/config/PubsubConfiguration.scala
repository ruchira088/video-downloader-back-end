package com.ruchij.core.config

import com.ruchij.core.config.PureConfigReaders._
import com.ruchij.core.messaging.PubSub.PubsubType
import com.ruchij.migration.config.DatabaseConfiguration
import pureconfig.ConfigReader
import pureconfig.generic.auto._

final case class PubsubConfiguration(
  pubsubType: PubsubType,
  kafkaConfiguration: Option[KafkaConfiguration],
  redisConfiguration: Option[RedisConfiguration],
  databaseConfiguration: Option[DatabaseConfiguration]
)

object PubsubConfiguration {
  implicit val pubsubConfigurationConfigReader: ConfigReader[PubsubConfiguration] =
    ConfigReader.fromCursor {
      _.asObjectCursor
        .flatMap { objectCursor =>
          objectCursor
            .atKey("type")
            .flatMap { configCursor =>
              ConfigReader[PubsubType].from(configCursor)
            }
            .flatMap {
              case PubsubType.Kafka =>
                objectCursor
                  .atKey("kafka-configuration")
                  .flatMap { cursor =>
                    ConfigReader[KafkaConfiguration].from(cursor)
                  }
                  .map { kafkaConfiguration =>
                    PubsubConfiguration(
                      pubsubType = PubsubType.Kafka,
                      kafkaConfiguration = Some(kafkaConfiguration),
                      redisConfiguration = None,
                      databaseConfiguration = None
                    )
                  }

              case PubsubType.Redis =>
                objectCursor
                  .atKey("redis-configuration")
                  .flatMap { cursor =>
                    ConfigReader[RedisConfiguration].from(cursor)
                  }
                  .map { redisConfiguration =>
                    PubsubConfiguration(
                      pubsubType = PubsubType.Redis,
                      kafkaConfiguration = None,
                      redisConfiguration = Some(redisConfiguration),
                      databaseConfiguration = None
                    )
                  }

              case PubsubType.Doobie =>
                objectCursor
                  .atKey("database-configuration")
                  .flatMap { cursor =>
                    ConfigReader[DatabaseConfiguration].from(cursor)
                  }
                  .map { databaseConfiguration =>
                    PubsubConfiguration(
                      pubsubType = PubsubType.Doobie,
                      kafkaConfiguration = None,
                      redisConfiguration = None,
                      databaseConfiguration = Some(databaseConfiguration)
                    )
                  }
            }
        }
    }
}

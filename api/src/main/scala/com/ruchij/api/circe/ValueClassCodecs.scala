package com.ruchij.api.circe

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.core.circe.{Decoders, Encoders}
import io.circe.{Decoder, Encoder}

// The value classes that wrap a string are encoded in JSON as that string, and decoding rejects an empty string
object ValueClassCodecs {
  implicit val emailDecoder: Decoder[Email] = Decoders.stringWrapperDecoder(Email.apply)
  implicit val emailEncoder: Encoder[Email] = Encoders.stringWrapperEncoder(_.value)

  implicit val secretDecoder: Decoder[Secret] = Decoders.stringWrapperDecoder(Secret.apply)
  implicit val secretEncoder: Encoder[Secret] = Encoders.stringWrapperEncoder(_.value)

  implicit val passwordDecoder: Decoder[Password] = Decoders.stringWrapperDecoder(Password.apply)
  implicit val passwordEncoder: Encoder[Password] = Encoders.stringWrapperEncoder(_.value)

  implicit val hashedPasswordDecoder: Decoder[HashedPassword] = Decoders.stringWrapperDecoder(HashedPassword.apply)
  implicit val hashedPasswordEncoder: Encoder[HashedPassword] = Encoders.stringWrapperEncoder(_.value)
}

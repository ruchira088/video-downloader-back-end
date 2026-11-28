package com.ruchij.api.circe

import com.ruchij.api.daos.credentials.models.Credentials.HashedPassword
import com.ruchij.api.daos.user.models.Email
import com.ruchij.api.services.authentication.AuthenticationService.{Password, Secret}
import com.ruchij.core.circe.{Decoders, Encoders}
import io.circe.{Decoder, Encoder}

// The value classes that wrap a string are encoded in JSON as that string, and decoding rejects an empty string
object ValueClassCodecs {
  given emailDecoder: Decoder[Email] = Decoders.stringWrapperDecoder(Email.apply)
  given emailEncoder: Encoder[Email] = Encoders.stringWrapperEncoder(_.value)

  given secretDecoder: Decoder[Secret] = Decoders.stringWrapperDecoder(Secret.apply)
  given secretEncoder: Encoder[Secret] = Encoders.stringWrapperEncoder(_.value)

  given passwordDecoder: Decoder[Password] = Decoders.stringWrapperDecoder(Password.apply)
  given passwordEncoder: Encoder[Password] = Encoders.stringWrapperEncoder(_.value)

  given hashedPasswordDecoder: Decoder[HashedPassword] = Decoders.stringWrapperDecoder(HashedPassword.apply)
  given hashedPasswordEncoder: Encoder[HashedPassword] = Encoders.stringWrapperEncoder(_.value)
}

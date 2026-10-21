package com.ruchij.api.web.middleware

import cats.data.{Kleisli, NonEmptyList}
import cats.effect.IO
import com.ruchij.api.exceptions.{
  AuthenticationException,
  AuthorizationException,
  RangeNotSatisfiableException,
  ResourceConflictException
}
import com.ruchij.api.services.models.Context
import com.ruchij.core.exceptions._
import com.ruchij.core.test.IOSupport.runIO
import io.circe.DecodingFailure
import io.circe.parser.parse
import org.http4s._
import org.http4s.implicits._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers

class ExceptionHandlerSpec extends AnyFlatSpec with Matchers {

  private def createHandler(error: Throwable): Kleisli[IO, ContextRequest[IO, Context], Response[IO]] = {
    ExceptionHandler[IO, Context] {
      Kleisli { _ =>
        IO.raiseError(error)
      }
    }
  }

  private val testContext: Context = Context.RequestContext("test-request-id")
  private val testRequest: Request[IO] = Request[IO](Method.GET, uri"/test")
  private val contextRequest: ContextRequest[IO, Context] = ContextRequest(testContext, testRequest)

  "ExceptionHandler" should "return NotFound for ResourceNotFoundException" in runIO {
    val handler = createHandler(ResourceNotFoundException("Test resource not found"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.NotFound
    }
  }

  it should "return Unauthorized for AuthenticationException" in runIO {
    val handler = createHandler(AuthenticationException("Not authenticated"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.Unauthorized
    }
  }

  it should "return Forbidden for AuthorizationException" in runIO {
    val handler = createHandler(AuthorizationException("Not authorized"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.Forbidden
    }
  }

  it should "return BadRequest for ValidationException" in runIO {
    val handler = createHandler(ValidationException("Invalid input"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadRequest
    }
  }

  it should "return BadRequest for IllegalArgumentException" in runIO {
    val handler = createHandler(new IllegalArgumentException("Bad argument"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadRequest
    }
  }

  it should "return BadRequest for UnsupportedVideoUrlException" in runIO {
    val handler = createHandler(UnsupportedVideoUrlException(uri"https://unsupported.com/video"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadRequest
    }
  }

  it should "return BadGateway for JSoupException" in runIO {
    import org.jsoup.Jsoup
    val element = Jsoup.parse("<html></html>").body()
    val handler = createHandler(JSoupException.TextNotFoundInElementException(element))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadGateway
    }
  }

  it should "return BadGateway for ExternalServiceException" in runIO {
    val handler = createHandler(ExternalServiceException("External service failed"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadGateway
    }
  }

  it should "return Conflict for ResourceConflictException" in runIO {
    val handler = createHandler(ResourceConflictException("Resource already exists"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.Conflict
    }
  }

  it should "return InternalServerError for unknown exceptions" in runIO {
    val handler = createHandler(new RuntimeException("Unknown error"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.InternalServerError
    }
  }

  it should "handle AggregatedException by using first exception's status" in runIO {
    val exceptions = NonEmptyList.of(
      ResourceNotFoundException("First"),
      AuthenticationException("Second")
    )
    val handler = createHandler(AggregatedException(exceptions))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.NotFound
    }
  }

  it should "return BadRequest for DecodingFailure" in runIO {
    val decodingFailure = DecodingFailure("Invalid JSON", Nil)
    val handler = createHandler(decodingFailure)

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.BadRequest
    }
  }

  it should "pass through successful responses" in runIO {
    val successHandler = ExceptionHandler[IO, Context] {
      Kleisli { _ =>
        IO.pure(Response[IO](Status.Ok))
      }
    }

    successHandler.run(contextRequest).map { response =>
      response.status mustBe Status.Ok
    }
  }

  it should "remove authentication cookie on AuthenticationException" in runIO {
    val handler = createHandler(AuthenticationException("Invalid session"))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.Unauthorized
      response.cookies.find(_.name == Authenticator.CookieName).map(_.content) must (be(empty) or be(Some("")))
    }
  }

  it should "handle nested exceptions by extracting cause" in runIO {
    val cause = ResourceNotFoundException("Original cause")
    val wrapper = new RuntimeException("Wrapper", cause)
    val handler = createHandler(wrapper)

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.InternalServerError
    }
  }

  it should "include error message in response body" in runIO {
    val errorMessage = "Specific error message"
    val handler = createHandler(ResourceNotFoundException(errorMessage))

    handler.run(contextRequest).flatMap { response =>
      response.as[String].map { body =>
        body must include(errorMessage)
      }
    }
  }

  it should "handle AggregatedException with multiple error messages" in runIO {
    val exceptions = NonEmptyList.of(
      ValidationException("Error 1"),
      ValidationException("Error 2")
    )
    val handler = createHandler(AggregatedException(exceptions))

    handler.run(contextRequest).flatMap { response =>
      response.as[String].map { body =>
        body must include("Error 1")
        body must include("Error 2")
      }
    }
  }

  private def errorMessages(response: Response[IO]): IO[List[String]] =
    response.as[String].map { body =>
      parse(body).flatMap(_.hcursor.downField("errorMessages").as[List[String]]).getOrElse(List.empty)
    }

  it should "not expose the message of an unexpected exception" in runIO {
    val handler =
      createHandler(new RuntimeException("Connection to jdbc:postgresql://db:5432/video-downloader refused"))

    handler.run(contextRequest).flatMap { response =>
      errorMessages(response).map { messages =>
        response.status mustBe Status.InternalServerError
        messages mustBe List(ExceptionHandler.InternalServerErrorMessage)
      }
    }
  }

  it should "not expose the message of the cause of an unexpected exception" in runIO {
    val handler =
      createHandler(new RuntimeException("Wrapper", new IllegalStateException("/home/app/videos is not writable")))

    handler.run(contextRequest).flatMap { response =>
      errorMessages(response).map { messages =>
        response.status mustBe Status.InternalServerError
        messages mustBe List(ExceptionHandler.InternalServerErrorMessage)
      }
    }
  }

  it should "return a generic message for an unexpected exception without a message" in runIO {
    val handler = createHandler(new NullPointerException())

    handler.run(contextRequest).flatMap { response =>
      errorMessages(response).map { messages =>
        response.status mustBe Status.InternalServerError
        messages mustBe List(ExceptionHandler.InternalServerErrorMessage)
      }
    }
  }

  it should "not return a null message for a mapped exception without a message" in runIO {
    val handler = createHandler(ValidationException(""))

    handler.run(contextRequest).flatMap { response =>
      errorMessages(response).map { messages =>
        response.status mustBe Status.BadRequest
        messages mustBe List(Status.BadRequest.reason)
      }
    }
  }

  private def messagesFor(error: Throwable): IO[(Status, List[String])] =
    createHandler(error).run(contextRequest).flatMap(response => errorMessages(response).map(response.status -> _))

  it should "not expose the message of an ExternalServiceException" in runIO {
    messagesFor(ExternalServiceException("Failed 403 Forbidden response for HEAD https://cdn.internal:8443/v.mp4"))
      .map { result =>
        result mustBe (Status.BadGateway -> List(ExceptionHandler.BadGatewayMessage))
      }
  }

  it should "not expose the page content in a JSoupException" in runIO {
    import org.jsoup.Jsoup
    val element = Jsoup.parse("<html><body><div data-token='secret'></div></body></html>").body()

    messagesFor(JSoupException.TextNotFoundInElementException(element)).map { result =>
      result mustBe (Status.BadGateway -> List(ExceptionHandler.BadGatewayMessage))
    }
  }

  it should "not expose the message of an IllegalArgumentException" in runIO {
    messagesFor(new IllegalArgumentException("Key contains extra terms: auth-token-abc")).map { result =>
      result mustBe (Status.BadRequest -> List(ExceptionHandler.BadRequestMessage))
    }
  }

  it should "not expose the details of a message body failure" in runIO {
    messagesFor(InvalidMessageBodyFailure("Could not decode JSON: { 'upstream': 'internal' }")).map { result =>
      result mustBe (Status.BadRequest -> List(ExceptionHandler.BadRequestMessage))
    }
  }

  it should "return the sanitized message of a ParseFailure" in runIO {
    messagesFor(ParseFailure("X-Request-ID header value is empty", "internal details")).map { result =>
      result mustBe (Status.BadRequest -> List("X-Request-ID header value is empty"))
    }
  }

  it should "keep the message of a ValidationException" in runIO {
    messagesFor(ValidationException("Invalid status value: Active")).map { result =>
      result mustBe (Status.BadRequest -> List("Invalid status value: Active"))
    }
  }

  it should "keep the messages of mapped exceptions but hide unexpected ones in an AggregatedException" in runIO {
    val handler =
      createHandler(
        AggregatedException(NonEmptyList.of(ValidationException("Invalid page size"), new RuntimeException("secret")))
      )

    handler.run(contextRequest).flatMap { response =>
      errorMessages(response).map { messages =>
        response.status mustBe Status.BadRequest
        messages mustBe List("Invalid page size", ExceptionHandler.InternalServerErrorMessage)
      }
    }
  }

  it should "return RangeNotSatisfiable with the size of the resource for RangeNotSatisfiableException" in runIO {
    val handler = createHandler(RangeNotSatisfiableException(1024))

    handler.run(contextRequest).map { response =>
      response.status mustBe Status.RangeNotSatisfiable
      response.headers.get(org.typelevel.ci.CIString("Content-Range")).map(_.head.value) mustBe Some("bytes */1024")
    }
  }
}

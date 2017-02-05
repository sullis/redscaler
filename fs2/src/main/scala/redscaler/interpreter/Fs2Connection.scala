package redscaler.interpreter

import com.typesafe.scalalogging.StrictLogging
import fs2.io.tcp.Socket
import fs2.util.syntax._
import fs2.util.{Applicative, Async, Catchable}
import fs2.{Chunk, Pull, Stream}
import redscaler._
import redscaler.interpreter.ArgConverters.stringArgConverter
import redscaler.interpreter.RedisConstants._
import redscaler.pubsub.SubscriberResponse

import scala.collection.mutable
import scala.concurrent.duration._

class Fs2Connection[F[_]: Applicative: Catchable](val redisClient: Stream[F, Socket[F]])
    extends Connection[F]
    with RedisResponseHandler[F]
    with StrictLogging {

  val writeTimeout = Some(2.seconds)
  val readTimeout  = Some(2.seconds)
  val maxBytesRead = 1024

  def runKeyCommand(command: String, key: String, args: Vector[Byte]*): F[ErrorOr[RedisResponse]] = {
    runCommand(command, stringArgConverter(key) +: args)
  }

  def runNoArgCommand(command: String): F[ErrorOr[RedisResponse]] = {
    runCommand(command, Seq.empty)
  }

  def runCommand[T](command: String, args: Seq[Vector[Byte]]): F[ErrorOr[RedisResponse]] = {
    val writeAndRead: (Socket[F]) => Stream[F, Byte] = { socket =>
      Stream
        .chunk(createCommand(command, args))
        .to(socket.writes(writeTimeout))
        .drain
        .onFinalize(socket.endOfOutput) ++
        socket.reads(maxBytesRead, readTimeout)
    }
    redisClient
      .flatMap(writeAndRead)
      .pull(handleResponse)
      .runLast
      .map(_.fold[ErrorOr[RedisResponse]](Left(EmptyResponse))(Right(_)))
  }

  def createCommand(command: String, args: Seq[Vector[Byte]]): Chunk[Byte] = {
    val bytes = new mutable.ListBuffer() +=
        ASTERISK_BYTE ++= intCrlf(args.length + 1) +=
        DOLLAR_BYTE ++= intCrlf(command.length) ++=
        command.getBytes ++= CRLF ++=
        args.flatMap(arg => (DOLLAR_BYTE +: intCrlf(arg.length)) ++ arg ++ CRLF)

    logger.trace(s"command created: ${bytes.result().toVector.asString}")

    Chunk.bytes(bytes.result().toArray)
  }

  def subscribeAndPull(command: Chunk[Byte])(implicit async: Async[F]): Stream[F, ErrorOr[SubscriberResponse]] = {
    val writeCommand: (Socket[F]) => Stream[F, Socket[F]] = { socket: Socket[F] =>
      Stream.chunk(command).to(socket.writes(None)).drain ++ Stream.emit(socket)
    }
    redisClient
      .flatMap(writeCommand)
      .pull {
        _.receive1 {
          case (socket, handle) =>
            Pull.outputs(socket.reads(1024, timeout = None).repeatPull(handleResponse)) as handle
        }
      }
      .map {
        case ArrayResponse(
            List(BulkResponse(Some(messageType)), BulkResponse(Some(channelName)), BulkResponse(Some(messageBody)))) =>
          val subscriberResponse = SubscriberResponse(messageType.asString, channelName.asString, messageBody)
          logger.debug(s"got sub response: $subscriberResponse")
          Right(subscriberResponse)
        case ArrayResponse(
            List(BulkResponse(Some(messageType)),
                 BulkResponse(Some(channelName)),
                 IntegerResponse(subscriberCount))) =>
          val subscriberResponse =
            SubscriberResponse(messageType.asString, channelName.asString, subscriberCount.toInt)
          logger.debug(s"got sub response: $subscriberResponse")
          Right(subscriberResponse)
        case response =>
          logger.debug(s"got unexpected response: $response")
          Left(UnexpectedResponse(response))
      }
  }

}

object Fs2Connection {
  def handleResponseWithErrorHandling[A](
      handler: PartialFunction[RedisResponse, A]): Function[ErrorOr[RedisResponse], ErrorOr[A]] = {
    case Left(error) =>
      Left(error)
    case Right(result) =>
      handler.lift(result).fold[ErrorOr[A]](Left(UnexpectedResponse(result)))(Right(_))
  }
}
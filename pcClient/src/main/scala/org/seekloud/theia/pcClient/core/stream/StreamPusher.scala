package org.seekloud.theia.pcClient.core.stream


import java.io.{File, FileOutputStream}
import java.nio.{ByteBuffer, ShortBuffer}
import java.nio.channels.{Channels, Pipe}

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, StashBuffer, TimerScheduler}
import org.seekloud.theia.capture.protocol.Messages.EncoderType
import org.seekloud.theia.pcClient.common.Constants
import org.seekloud.theia.pcClient.core.collector.CaptureActor
import org.seekloud.theia.pcClient.core.rtp.TsPacket
import org.seekloud.theia.pcClient.utils.TimeUtil
import org.seekloud.theia.rtpClient.Protocol.{AuthRsp, CloseSuccess, PushStreamError}
import org.seekloud.theia.rtpClient.{Protocol, PushStreamClient}
import org.slf4j.LoggerFactory

import concurrent.duration._
import scala.util.{Failure, Success, Try}
import java.io.FileOutputStream
import java.io.File
import java.net.{DatagramPacket, DatagramSocket, InetAddress}

import org.bytedeco.javacv.FFmpegFrameGrabber
import sun.nio.ch.DirectBuffer

/**
  * User: TangYaruo
  * Date: 2019/8/20
  * Time: 13:41
  */
object StreamPusher {

  private val log = LoggerFactory.getLogger(this.getClass)

  type PushCommand = Protocol.Command

  final case class InitRtpClient(pushClient: PushStreamClient) extends PushCommand

  final case object PushAuth extends PushCommand

  final case object AuthTimeOut extends PushCommand

  final case object PushStream extends PushCommand

  final case object StopPush extends PushCommand

  final case object StopSelf extends PushCommand

  private object PUSH_STREAM_TIMER_KEY

  def create(
    liveId: String,
    liveCode: String,
    parent: ActorRef[LiveManager.LiveCommand],
    captureActor: ActorRef[CaptureActor.CaptureCommand]
  ): Behavior[PushCommand] =
    Behaviors.setup[PushCommand] { ctx =>
      log.info(s"StreamPusher-$liveId is staring.")
      implicit val stashBuffer: StashBuffer[PushCommand] = StashBuffer[PushCommand](Int.MaxValue)
      Behaviors.withTimers[PushCommand] { implicit timer =>
        init(liveId, liveCode, parent, captureActor, None)
      }
    }

  private def init(
    liveId: String,
    liveCode: String,
    parent: ActorRef[LiveManager.LiveCommand],
    captureActor: ActorRef[CaptureActor.CaptureCommand],
    pushClient: Option[PushStreamClient],
  )(
    implicit timer: TimerScheduler[PushCommand],
    stashBuffer: StashBuffer[PushCommand]
  ): Behavior[PushCommand] =
    Behaviors.receive[PushCommand] { (ctx, msg) =>
      msg match {
        case msg: InitRtpClient =>
          log.info(s"StreamPusher-$liveId init rtpClient.")
          Try(msg.pushClient.authStart()) match {
            case Success(_) =>
              log.info(s"rtp client initial successfully, ip: ${msg.pushClient.getIp}")
              ctx.self ! PushAuth
              init(liveId, liveCode, parent, captureActor, Some(msg.pushClient))
            case Failure(e) =>
              log.info(s"rtp client initial failed, ip: ${msg.pushClient.getIp}, error msg: ${e.getMessage}")
              parent ! LiveManager.InitRtpFailed
              Behaviors.same
          }


        case PushAuth =>
          pushClient.foreach(_.auth(liveId, liveCode))
          timer.startSingleTimer(AuthTimeOut, AuthTimeOut, 10.seconds)
          Behaviors.same

        case msg: AuthRsp =>
          timer.cancel(AuthTimeOut)
          if (msg.ifSuccess) {
            log.info(s"StreamPusher-$liveId auth success!")
            val mediaPipe = Pipe.open() //client -> sink -> source -> server
            val sink = mediaPipe.sink() //数据会被写到sink通道、从source通道读取
            val source = mediaPipe.source()
            val dataBuff = ByteBuffer.allocate(7 * TsPacket.tsPacketSize)
            captureActor ! CaptureActor.StartEncode(Right(Channels.newOutputStream(sink)))
            ctx.self ! PushStream
//            val file = new File(s"theia-直播-${TimeUtil.timeStamp2DetailDate(System.currentTimeMillis()).replaceAll("-", "").replaceAll(":", "").replaceAll(" ", "")}.ts")
//            val outStream = new FileOutputStream(file)
//            val frameGrabber = new FFmpegFrameGrabber("D:\\视频\\1.mp4")
//            frameGrabber.start()
//            frameGrabber.setFrameNumber(30)
            pushing(liveId, liveCode, parent, pushClient.get, captureActor, source, dataBuff,null,false)
          } else {
            log.debug(s"Push liveId-$liveId liveCode-$liveCode auth failed.")
            Behaviors.same
          }

        case AuthTimeOut =>
          log.info(s"StreamPusher-$liveId auth timeout, try again.")
          ctx.self ! PushAuth
          Behaviors.same

        case StopPush =>
          log.info(s"StreamPusher-$liveId StopPush in init.")
          parent ! LiveManager.PusherStopped
          Behaviors.stopped

        case x =>
          log.warn(s"unHandled msg in init: $x")
          Behaviors.unhandled
      }
    }
  private object ENSURE_STOP_PUSH
//  val socket = new DatagramSocket()
//  val port = 50012
//  private val addr = InetAddress.getByName("127.0.0.1")

//  pushing(liveId, liveCode, parent, pushClient.get, captureActor, source, dataBuff,null,false)

  private def pushing(
    liveId: String,
    liveCode: String,
    parent: ActorRef[LiveManager.LiveCommand],
    pushClient: PushStreamClient,
    //    mediaActor: ActorRef[MediaActor.MediaCommand],
    captureActor: ActorRef[CaptureActor.CaptureCommand],
    mediaSource: Pipe.SourceChannel,
    dataBuff: ByteBuffer,
    outputStream: FileOutputStream,
    isTestPush:Boolean
//    fFmpegFrameGrabber: FFmpegFrameGrabber
  )(
    implicit timer: TimerScheduler[PushCommand],
    stashBuffer: StashBuffer[PushCommand]
  ): Behavior[PushCommand] =
    Behaviors.receive[PushCommand] { (ctx, msg) =>
      msg match {
        case PushStream =>
          try {
            dataBuff.clear()
            val bytesRead = mediaSource.read(dataBuff)
            dataBuff.flip()
//            val frame = fFmpegFrameGrabber.grab()
//            if(frame!=null && frame.image!=null){
//              val i = ImgConv.toBufferedImage(frame)
//              val raster = i.getRaster
//              import java.awt.image.DataBufferByte
//              val dataBuffer = raster.getDataBuffer.asInstanceOf[DataBufferByte]
//              pushClient.pushStreamData(liveId,dataBuffer.getData)
//            }
            if (bytesRead != -1) {
              //              log.debug(s"bytesRead: $bytesRead")
              //              log.debug(s"data length: ${dataBuff.remaining()}")

              val s = dataBuff.array().take(dataBuff.remaining())

//              val datagramPacket = new DatagramPacket(s, s.length, addr, port)
//              socket.send(datagramPacket)

              if(isTestPush)outputStream.write(s)
              pushClient.pushStreamData(liveId, s)
            }
          } catch {
            case e: Exception =>
              log.warn(s"StreamPusher-$liveId PushStream error: $e")
          }
          ctx.self ! PushStream
          Behaviors.same

        case msg: PushStreamError =>
          log.info(s"StreamPusher-$liveId push ${msg.liveId} error: ${msg.msg}")
          ctx.self ! PushAuth
          init(liveId, liveCode, parent, captureActor, Some(pushClient))

        case StopPush =>
          log.info(s"StreamPusher-$liveId is stopping.")
          captureActor ! CaptureActor.StopEncode(EncoderType.STREAM)
          pushClient.close()
          dataBuff.clear()
          timer.startPeriodicTimer(ENSURE_STOP_PUSH,StopPush,5000.milliseconds)
          Behaviors.same

        case CloseSuccess =>
          log.info(s"StreamPusher-$liveId is stopped.")
          timer.cancel(ENSURE_STOP_PUSH)
          if(isTestPush){
            outputStream.flush()
            outputStream.close()
          }
          parent ! LiveManager.PusherStopped
          Behaviors.stopped

        case x =>
          log.warn(s"unknown msg in pushing: $x")
          Behaviors.unhandled
      }
    }


}

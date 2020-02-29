package org.seekloud.theia.capture.core

import java.awt.image.BufferedImage
import java.io.FileInputStream
import java.nio.ShortBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.{LinkedBlockingDeque, ScheduledFuture, ScheduledThreadPoolExecutor, TimeUnit}

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import javax.imageio.ImageIO
import org.bytedeco.javacv.{FFmpegFrameRecorder, Java2DFrameConverter}
import org.seekloud.theia.capture.sdk.MediaCapture.executor
import org.seekloud.theia.capture.protocol.Messages
import org.seekloud.theia.capture.protocol.Messages.{EncodeException, EncoderType}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
  * User: TangYaruo
  * Date: 2019/8/28
  * Time: 17:54
  * Description: 用来存储视频对视频的编码
  */
object EncodeActor {

  private val log = LoggerFactory.getLogger(this.getClass)
  var debug: Boolean = true
  private var needTimeMark: Boolean = false

  def debug(msg: String): Unit = {
    if (debug) log.debug(msg)
  }

  sealed trait Command

  final case object StartEncodeLoop extends Command

  final case object EncodeLoop extends Command

  final case class EncodeSamples(sampleRate: Int, channel: Int, samples: ShortBuffer) extends Command

  final case object StopEncode extends Command

  final case class ShieldImage(op:Boolean) extends Command


  def create(
    replyTo: ActorRef[Messages.ReplyToCommand],
    encodeType: EncoderType.Value,
    encoder: FFmpegFrameRecorder,
    imageCache: LinkedBlockingDeque[Messages.LatestFrame],
    needImage: Boolean,
    needSound: Boolean,
    isDebug: Boolean,
    needTimestamp: Boolean
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      log.info(s"EncodeActor-$encodeType is starting...")
      debug = isDebug
      needTimeMark = needTimestamp
      Future {
        log.info(s"Encoder-$encodeType is starting...")
        encoder.startUnsafe()
        log.info(s"Encoder-$encodeType started.")
      }.onComplete {
        case Success(_) =>
          ctx.self ! StartEncodeLoop
        case Failure(e) =>
          encodeType match {
            case EncoderType.STREAM =>
              log.info(s"streamEncoder start failed: $e")
              replyTo ! Messages.StreamCannotBeEncoded
            case EncoderType.FILE =>
              log.info(s"fileEncoder start failed: $e")
              replyTo ! Messages.CannotSaveToFile
              ctx.self ! StopEncode
            case EncoderType.BILIBILI =>
              log.info(s"fileEncoder start failed: $e")
              replyTo ! Messages.CannotRecordToBiliBili
          }

      }
      working(replyTo, encodeType, encoder, imageCache, new Java2DFrameConverter(), needImage, needSound)
    }


  private def working(
    replyTo: ActorRef[Messages.ReplyToCommand],
    encodeType: EncoderType.Value,
    encoder: FFmpegFrameRecorder,
    imageCache: LinkedBlockingDeque[Messages.LatestFrame],
    imageConverter: Java2DFrameConverter,
    needImage: Boolean,
    needSound: Boolean,
    encodeLoop: Option[ScheduledFuture[_]] = None,
    encodeExecutor: Option[ScheduledThreadPoolExecutor] = None,
    frameNumber: Int = 0,
    noImage:Boolean  = false,
  ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case StartEncodeLoop =>
          debug(s"frameRate: ${encoder.getFrameRate}, interval: ${1000 / encoder.getFrameRate}")

          val encodeLoopExecutor = new ScheduledThreadPoolExecutor(1)
          val loop = encodeLoopExecutor.scheduleAtFixedRate(
            () => {
              ctx.self ! EncodeLoop
            },
            0,
            ((1000.0 / encoder.getFrameRate) * 1000).toLong,
            TimeUnit.MICROSECONDS
          )

          working(replyTo, encodeType, encoder, imageCache, imageConverter, needImage, needSound, Some(loop), Some(encodeLoopExecutor), frameNumber,noImage)

        case EncodeLoop =>
          if (needImage) {
            try {
              val latestImage = imageCache.peek()
              if (latestImage != null) {
                encoder.setTimestamp((frameNumber * (1000.0 / encoder.getFrameRate) * 1000).toLong)
                if (!needTimeMark) {

                  if(noImage){
                    val iw = latestImage.frame.imageWidth
                    val ih = latestImage.frame.imageHeight
                    val fstream = new FileInputStream("file:/Users/haoxue/Downloads/black.jpg")
                    val input = ImageIO.read(fstream);
                    val bufferedImage = new BufferedImage(iw,ih,BufferedImage.TYPE_INT_RGB)
                    val grafic = bufferedImage.createGraphics()
                    grafic.drawImage(input,0,0,null)
                    encoder.record(imageConverter.convert(bufferedImage))
                  }else{
                    encoder.record(latestImage.frame)
                  }

                } else {

                  if(noImage){
                    val iw = latestImage.frame.imageWidth
                    val ih = latestImage.frame.imageHeight
                    val fstream = new FileInputStream("file:/Users/haoxue/Downloads/black.jpg")
                    val input = ImageIO.read(fstream);
                    val bImg = new BufferedImage(iw,ih,BufferedImage.TYPE_INT_RGB)
                    val grafic = bImg.createGraphics()
                    grafic.drawImage(input,0,0,null)
                    val ts = if (CaptureManager.timeGetter != null) CaptureManager.timeGetter() else System.currentTimeMillis()
                    val date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:S").format(ts)
                    bImg.getGraphics.drawString(date, iw / 10, ih / 10)
                    encoder.record(imageConverter.convert(bImg))

                  }else{
                    val iw = latestImage.frame.imageWidth
                    val ih = latestImage.frame.imageHeight
                    val bImg = imageConverter.convert(latestImage.frame)
                    val ts = if (CaptureManager.timeGetter != null) CaptureManager.timeGetter() else System.currentTimeMillis()
                    val date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss:S").format(ts)
                    bImg.getGraphics.drawString(date, iw / 10, ih / 10)
                    encoder.record(imageConverter.convert(bImg))
                  }

                }
              }
            } catch {
              case ex: Exception =>
                log.error(s"[$encodeType] encode image frame error: $ex")
                if(ex.getMessage.startsWith("av_interleaved_write_frame() error")){
                  replyTo ! EncodeException(ex)
                  ctx.self ! StopEncode
                }
            }
          }
          working(replyTo, encodeType, encoder, imageCache, imageConverter, needImage, needSound, encodeLoop, encodeExecutor, frameNumber + 1,noImage)

        case msg: EncodeSamples =>
          if (encodeLoop.nonEmpty) {
            try {
              encoder.recordSamples(msg.sampleRate, msg.channel, msg.samples)
            } catch {
              case ex: Exception =>
                log.warn(s"Encoder-$encodeType encode samples error: $ex")
            }
          }
          Behaviors.same

        case StopEncode =>
          log.info(s"encoding stopping...")
          encodeLoop.foreach(_.cancel(false))
          encodeExecutor.foreach(_.shutdown())
          try {
            encoder.releaseUnsafe()
            log.info(s"release encode resources.")
          } catch {
            case ex: Exception =>
              log.warn(s"release encode error: $ex")
              ex.printStackTrace()
          }
          Behaviors.stopped

        case msg:ShieldImage =>

          //屏蔽图像

          log.debug(s"encode actor sheild image success")
            working(replyTo,encodeType,encoder,imageCache,imageConverter,needImage,needSound,encodeLoop,encodeExecutor,frameNumber,msg.op)

          Behaviors.same
        case x =>
          log.warn(s"unknown msg in working: $x")
          Behaviors.unhandled
      }
    }

}

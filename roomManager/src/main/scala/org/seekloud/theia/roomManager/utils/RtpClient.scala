package org.seekloud.theia.roomManager.utils

import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol.GetLiveInfoRsp
import SecureUtil.genPostEnvelope
import org.slf4j.LoggerFactory
import org.seekloud.theia.roomManager.Boot.{executor, scheduler, system, timeout}
import org.seekloud.theia.roomManager.common.AppSettings

import org.seekloud.theia.roomManager.protocol.CommonInfoProtocol._
import scala.concurrent.Future

/**
  * created by byf on 2019.8.28
  **/
object RtpClient extends HttpUtil {

  import io.circe.generic.auto._
  import io.circe.syntax._
  import io.circe.parser.decode

  case class GetLiveInfoReq(
    num: Int
  )

  private val log = LoggerFactory.getLogger(this.getClass)
  val processorBaseUrl = s"http://${AppSettings.rtpIp}:${AppSettings.rtpPort}/theia/rtpServer/api"

  def getLiveInfoFunc(num: Int): Future[Either[String, GetLiveInfoRsp]] = {
    log.debug("get live info")
    val url = processorBaseUrl + "/getLiveInfo"
    val req = GetLiveInfoReq(num).asJson.noSpaces
    val auth = genPostEnvelope("roomManager", System.nanoTime().toString, req, "484ec7db9e39bc4b5e3d").asJson.noSpaces
    postJsonRequestSend("getLiveInfo", url, List(), auth, timeOut = 60 * 1000, needLogRsp = false).map {
      case Right(v) =>
        decode[GetLiveInfoRsp](v) match {
          case Right(data) =>
            if (data.liveInfo.nonEmpty)
              Right(data)
            else
              Left(s"${data.msg}")
          case Left(e) =>
            log.error(s"getLiveInfo decode error : $e")
            Left(s"getLiveInfo decode error : $e")
        }
      case Left(error) =>
        log.error(s"getLiveInfo postJsonRequestSend error : $error")
        Left(s"getLiveInfo postJsonRequestSend error : $error")
    }
  }

  def testLiveInfoFunc(): Future[Either[String, GetLiveInfoRsp]] = {
    val url = "http://localhost:30382/theia/roomManager/rtp" + "/getLiveInfo"
    val req = genPostEnvelope("processor", System.nanoTime().toString, "{}", "0379a0aaff63c1ce").asJson.noSpaces
    postJsonRequestSend("getLiveInfo", url, List(), req, timeOut = 60 * 1000, needLogRsp = false).map {
      case Right(v) =>
        decode[GetLiveInfoRsp](v) match {
          case Right(data) =>
            log.debug("success")
            Right(data)
          case Left(e) =>
            log.error(s"getLiveInfo decode error : $e")
            Left(s"getLiveInfo decode error : $e")
        }
      case Left(error) =>
        log.error(s"getLiveInfo postJsonRequestSend error : $error")
        Left(s"getLiveInfo postJsonRequestSend error : $error")
    }
  }

}

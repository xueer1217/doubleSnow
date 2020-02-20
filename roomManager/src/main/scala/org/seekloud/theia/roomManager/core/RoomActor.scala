package org.seekloud.theia.roomManager.core

import akka.actor
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import org.seekloud.byteobject.MiddleBufferInJvm
import org.seekloud.theia.protocol.ptcl.CommonInfo._
import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol.{GetLiveInfoRsp, GetLiveInfoRsp4RM}
import org.seekloud.theia.protocol.ptcl.client2Manager.websocket.AuthProtocol
import org.seekloud.theia.protocol.ptcl.client2Manager.websocket.AuthProtocol.{HostCloseRoom, _}
import org.seekloud.theia.roomManager.Boot.{emailActor, executor, roomManager}
import org.seekloud.theia.roomManager.common.AppSettings.{distributorIp, distributorPort}
import org.seekloud.theia.roomManager.common.Common
import org.seekloud.theia.roomManager.common.Common.{Like, Role}
import org.seekloud.theia.roomManager.core.EmailActor.InviteJoin
import org.seekloud.theia.roomManager.core.RoomManager.GetRtmpLiveInfo
import org.seekloud.theia.roomManager.models.dao.{RecordDao, RoomDao, StatisticDao, UserInfoDao}
import org.seekloud.theia.roomManager.protocol.ActorProtocol
import org.seekloud.theia.roomManager.protocol.ActorProtocol.BanOnAnchor
import org.seekloud.theia.roomManager.protocol.CommonInfoProtocol.WholeRoomInfo
import org.seekloud.theia.roomManager.utils.{DistributorClient, ProcessorClient, RtpClient}
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}
import org.seekloud.theia.roomManager.core.UserActor

object RoomActor {

  import org.seekloud.byteobject.ByteObject._

  import scala.language.implicitConversions

  private val log = LoggerFactory.getLogger(this.getClass)

  trait Command

  final case class ChildDead[U](name: String, childRef: ActorRef[U]) extends Command with RoomManager.Command

  //private final case object DelayUpdateRtmpKey

  private final case class SwitchBehavior(
    name: String,
    behavior: Behavior[Command],
    durationOpt: Option[FiniteDuration] = None,
    timeOut: TimeOut = TimeOut("busy time error")
  ) extends Command

  private case class TimeOut(msg: String) extends Command

  private final case object BehaviorChangeKey

  private[this] def switchBehavior(ctx: ActorContext[Command],
    behaviorName: String, behavior: Behavior[Command], durationOpt: Option[FiniteDuration] = None, timeOut: TimeOut = TimeOut("busy time error"))
    (implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command]) = {
    timer.cancel(BehaviorChangeKey)
    durationOpt.foreach(timer.startSingleTimer(BehaviorChangeKey, timeOut, _))
    stashBuffer.unstashAll(ctx, behavior)
  }

  final case class TestRoom(roomInfo: RoomInfo) extends Command

  final case class GetRoomInfo(replyTo: ActorRef[RoomInfo]) extends Command //考虑后续房间的建立不依赖ws
  final case class UpdateRTMP(rtmp: String) extends Command

  private final val InitTime = Some(5.minutes)

  def create(roomId: Long): Behavior[Command] = {
    Behaviors.setup[Command] { ctx =>
      implicit val stashBuffer = StashBuffer[Command](Int.MaxValue)
      log.debug(s"${ctx.self.path} setup")
      Behaviors.withTimers[Command] { implicit timer =>
        implicit val sendBuffer: MiddleBufferInJvm = new MiddleBufferInJvm(8192)
        val subscribers = mutable.HashMap.empty[(Long, Boolean), ActorRef[UserActor.Command]] //??什么含义
        init(roomId, subscribers)
      }
    }
  }

  private def init(
    roomId: Long,
    subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]],
    roomInfoOpt: Option[RoomInfo] = None
  )
    (
      implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command],
      sendBuffer: MiddleBufferInJvm
    ): Behavior[Command] = {
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {

        case ActorProtocol.StartRoom4Anchor(userId, `roomId`, actor) =>
          log.debug(s"${ctx.self.path} 用户id=$userId 开启了新的视频会议 roomid=$roomId")
          subscribers.put((userId, false), actor)
          for {
            res <- RtpClient.getLiveInfoFunc(1)
            userTableOpt <- UserInfoDao.searchById(userId)
            roomIdOpt <- RoomDao.getRoomInfo(roomId)
            //            rtmpOpt <- ProcessorClient.getmpd(roomId)
          } yield {

            res match {
              case Right(r) =>
                val rsp = r.liveInfo.get.head
                log.debug(s"主播获得了liveid：${rsp.liveId} liveCode:${rsp.liveCode}")
                if (userTableOpt.nonEmpty && roomIdOpt.nonEmpty) {
                  val uinfo = userTableOpt.get
                  val rinfo = roomIdOpt.get
                  val roomInfo = RoomInfo(roomId, rinfo.roomName, rinfo.roomDesc, uinfo.uid, uinfo.userName,
                    UserInfoDao.getHeadImg(uinfo.headImg),
                    RoomDao.getCoverImg(rinfo.coverImg),
                    rtmp = Some(rsp.liveId)
                  )
                  DistributorClient.startPull(roomId, rsp.liveId).map {
                    case Right(r) =>
                      log.info(s"distributor startPull succeed, get live address: ${r.liveAdd}")
                      dispatchTo(subscribers)(List((userId, false)), StartLiveRsp(Some(rsp)))
                      roomInfo.mpd = Some(r.liveAdd)
                      val startTime = r.startTime
                      ctx.self ! SwitchBehavior("idle", idle(WholeRoomInfo(roomInfo), mutable.HashMap(Role.host -> mutable.HashMap(userId -> rsp)), subscribers, mutable.HashMap[Long, LiveInfo](), mutable.ListBuffer[Long](), mutable.Set[Long](), startTime, 0))

                    case Left(e) =>
                      log.error(s"distributor startPull error: $e")
                      dispatchTo(subscribers)(List((userId, false)), StartLiveRefused4LiveInfoError)
                      ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
                  }

                } else {
                  log.debug(s"${ctx.self.path} 开始直播被拒绝，数据库中没有该用户或该房间信息的数据，userId=$userId")
                  dispatchTo(subscribers)(List((userId, false)), StartLiveRefused)
                  ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
                }
              case Left(error) =>
                log.debug(s"${ctx.self.path} 开始直播被拒绝，请求rtp server解析失败，error:$error")
                dispatchTo(subscribers)(List((userId, false)), StartLiveRefused)
                ctx.self ! SwitchBehavior("init", init(roomId, subscribers))
            }
          }
          switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))

        case GetRoomInfo(replyTo) =>
          if (roomInfoOpt.nonEmpty) {
            replyTo ! roomInfoOpt.get
          } else {
            log.debug("房间信息未更新")
            replyTo ! RoomInfo(-1, "", "", -1l, "", "", "", -1, -1)
          }
          Behaviors.same

        case TestRoom(roomInfo) =>
          //仅用户测试使用空房间
          idle(WholeRoomInfo(roomInfo), mutable.HashMap[Int, mutable.HashMap[Long, LiveInfo]](), subscribers, mutable.HashMap[Long, LiveInfo](), mutable.ListBuffer[Long](), mutable.Set[Long](), System.currentTimeMillis(), 0)

        case ActorProtocol.AddUserActor4Test(userId, roomId, userActor) =>
          subscribers.put((userId, false), userActor)
          Behaviors.same

        case x =>
          log.debug(s"${ctx.self.path} recv an unknown msg:$x in init state...")
          Behaviors.same
      }
    }
  }

  private def idle(
    wholeRoomInfo: WholeRoomInfo, //可以考虑是否将主路的liveinfo加在这里，单独存一份连线者的liveinfo列表
    liveInfoMap: mutable.HashMap[Int, mutable.HashMap[Long, LiveInfo]],
    subscribe: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]], //需要区分订阅的用户的身份，注册用户还是临时用户(uid,是否是临时用户true:是)
    pullMap: mutable.HashMap[Long, LiveInfo], //uid =>连线时拉流的liveid
    userIdList: mutable.ListBuffer[Long], //主播以及已经参与连线的用户id
    liker: mutable.Set[Long],
    startTime: Long,
    totalView: Int,
    isJoinOpen: Boolean = false,
    inviteList: mutable.ListBuffer[Long] = mutable.ListBuffer.empty
  )
    (implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command],
      sendBuffer: MiddleBufferInJvm
    ): Behavior[Command] = {
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case ActorProtocol.AddUserActor4Test(userId, roomId, userActor) =>
          subscribe.put((userId, false), userActor)
          Behaviors.same

        case GetRoomInfo(replyTo) =>
          replyTo ! wholeRoomInfo.roomInfo
          Behaviors.same

        case UpdateRTMP(rtmp) =>
          //timer.cancel(DelayUpdateRtmpKey + wholeRoomInfo.roomInfo.roomId.toString)
          val newRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(rtmp = Some(rtmp)))
          log.debug(s"${ctx.self.path} 更新liveId=$rtmp,更新后的liveId=${newRoomInfo.roomInfo.rtmp}")
          idle(newRoomInfo, liveInfoMap, subscribe, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList)

        case ActorProtocol.WebSocketMsgWithActor(userId, roomId, wsMsg) =>
          handleWebSocketMsg(wholeRoomInfo, subscribe, liveInfoMap, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, dispatch(subscribe), dispatchTo(subscribe), inviteList)(ctx, userId, roomId, wsMsg)

        case GetRtmpLiveInfo(_, replyTo) =>
          log.debug(s"room${wholeRoomInfo.roomInfo.roomId}获取liveId成功")
          liveInfoMap.get(Role.host) match {
            case Some(value) =>
              replyTo ! GetLiveInfoRsp4RM(Some(value.values.head))
            case None =>
              log.debug(s"${ctx.self.path} no host live info,roomId=${wholeRoomInfo.roomInfo.roomId}")
              replyTo ! GetLiveInfoRsp4RM(None)

          }
          Behaviors.same

        case ActorProtocol.UpdateSubscriber(join, roomId, userId, temporary, userActorOpt) =>
          var viewNum = totalView
          //虽然房间存在，但其实主播已经关闭房间，这时的startTime=-1
          //向所有人发送主播已经关闭房间的消息
          log.info(s"-----roomActor get UpdateSubscriber id: $roomId")
          if (startTime == -1) {
            dispatchTo(subscribe)(List((userId, temporary)), NoAuthor)
          }
          else {
            if (join == Common.Subscriber.join) {
              // todo observe event
              viewNum += 1
              log.debug(s"${ctx.self.path}新用户加入房间roomId=$roomId,userId=$userId")
              subscribe.put((userId, temporary), userActorOpt.get)
            } else if (join == Common.Subscriber.left) {
              // todo observe event
              log.debug(s"${ctx.self.path}用户离开房间roomId=$roomId,userId=$userId")
              subscribe.remove((userId, temporary))
              if (liveInfoMap.contains(Role.audience)) {
                if (liveInfoMap(Role.audience).contains(userId)) {
                  wholeRoomInfo.roomInfo.rtmp match {
                    case Some(v) =>
                      if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId) {
                        liveInfoMap.remove(Role.audience)
                        ProcessorClient.closeRoom(roomId)
                        ctx.self ! UpdateRTMP(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
                        dispatch(subscribe)(AuthProtocol.AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
                        dispatch(subscribe)(RcvComment(-1l, "", s"the audience has shut the join in room $roomId"))
                      }
                    case None =>
                      log.debug("no host liveId when audience left room")
                  }
                }
              }
            }
          }
          //所有的注册用户
          val audienceList = subscribe.filterNot(_._1 == (wholeRoomInfo.roomInfo.userId, false)).keys.toList.filter(r => !r._2).map(_._1)
          val temporaryList = subscribe.filterNot(_._1 == (wholeRoomInfo.roomInfo.userId, false)).keys.toList.filter(r => r._2).map(_._1)
          UserInfoDao.getUserDes(audienceList).onComplete {
            case Success(rst) =>
              val temporaryUserDesList = temporaryList.map(r => UserDes(r, s"guest_$r", Common.DefaultImg.headImg))
              dispatch(subscribe)(UpdateAudienceInfo(rst ++ temporaryUserDesList))
            case Failure(_) =>

          }
          wholeRoomInfo.roomInfo.observerNum = subscribe.size - 1
          idle(wholeRoomInfo, liveInfoMap, subscribe, pullMap, userIdList, liker, startTime, viewNum, isJoinOpen, inviteList)

        case ActorProtocol.HostCloseRoom(roomId) =>
          log.debug(s"${ctx.self.path} host close the room")
          wholeRoomInfo.roomInfo.rtmp match {
            case Some(v) =>
              if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId) {
                ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
              }
            case None =>
          }
          //          if (wholeRoomInfo.roomInfo.rtmp.get != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
          //            ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
          if (startTime != -1l) {
            log.debug(s"${ctx.self.path} 主播向distributor发送finishPull请求")
            DistributorClient.finishPull(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
            roomManager ! RoomManager.DelaySeekRecord(wholeRoomInfo, totalView, roomId, startTime, liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
          }
          dispatchTo(subscribe)(subscribe.filter(r => r._1 != (wholeRoomInfo.roomInfo.userId, false)).keys.toList, HostCloseRoom())
          Behaviors.stopped

        case ActorProtocol.StartLiveAgain(roomId) =>
          log.debug(s"${ctx.self.path} the room actor has been exist,the host restart the room")
          for {
            data <- RtpClient.getLiveInfoFunc(1)
          } yield {
            data match {
              case Right(r) =>
                val rsp = r.liveInfo.get.head
                log.debug(s"主播获得liveid：${rsp.liveId} liveCode:${rsp.liveCode}")
                liveInfoMap.put(Role.host, mutable.HashMap(wholeRoomInfo.roomInfo.userId -> rsp))
                val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
                //timer.startSingleTimer(DelayUpdateRtmpKey + roomId.toString, UpdateRTMP(rsp.liveInfo.liveId), 4.seconds)
                DistributorClient.startPull(roomId, rsp.liveId).map {
                  case Right(r) =>
                    log.info("distributor startPull succeed")
                    val startTime = r.startTime
                    val newWholeRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(observerNum = 0, like = 0, mpd = Some(r.liveAdd), rtmp = Some(rsp.liveId)))
                    dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), StartLiveRsp(Some(rsp)))
                    ctx.self ! SwitchBehavior("idle", idle(newWholeRoomInfo, liveInfoMap, subscribe, pullMap, userIdList, liker, startTime, 0, isJoinOpen, inviteList))
                  case Left(e) =>
                    log.error(s"distributor startPull error: $e")
                    val newWholeRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(observerNum = 0, like = 0))
                    dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), StartLiveRsp(Some(rsp)))
                    ctx.self ! SwitchBehavior("idle", idle(newWholeRoomInfo, liveInfoMap, subscribe, pullMap, userIdList, liker, startTime, 0, isJoinOpen, inviteList))
                }


              case Left(str) =>
                log.debug(s"${ctx.self.path} 重新开始直播失败=$str")
                dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), StartLiveRefused4LiveInfoError)
                ctx.self ! ActorProtocol.HostCloseRoom(wholeRoomInfo.roomInfo.roomId)
                ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribe, pullMap, userIdList, liker, startTime, 0, isJoinOpen, inviteList))
            }
          }
          switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))

        case BanOnAnchor(roomId) =>
          //ProcessorClient.closeRoom(wholeRoomInfo.roomInfo.roomId)
          dispatchTo(subscribe)(subscribe.filter(r => r._1 != (wholeRoomInfo.roomInfo.userId, false)).keys.toList, HostCloseRoom())
          dispatchTo(subscribe)(List((wholeRoomInfo.roomInfo.userId, false)), AuthProtocol.BanOnAnchor)
          Behaviors.stopped

        case x =>
          log.debug(s"${ctx.self.path} recv an unknown msg $x")
          Behaviors.same
      }
    }
  }

  private def busy()
    (
      implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command],
      sendBuffer: MiddleBufferInJvm
    ): Behavior[Command] =
    Behaviors.receive[Command] { (ctx, msg) =>
      msg match {
        case SwitchBehavior(name, b, durationOpt, timeOut) =>
          switchBehavior(ctx, name, b, durationOpt, timeOut)

        case TimeOut(m) =>
          log.debug(s"${ctx.self.path} is time out when busy, msg=$m")
          Behaviors.stopped

        case x =>
          stashBuffer.stash(x)
          Behavior.same

      }
    }

  //websocket处理消息的函数
  /**
    * userActor --> roomManager --> roomActor --> userActor
    * roomActor
    * subscribers:map(userId,userActor)
    *
    *
    *
    **/
  private def handleWebSocketMsg(
    wholeRoomInfo: WholeRoomInfo,
    subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]], //包括主播在内的所有用户
    liveInfoMap: mutable.HashMap[Int, mutable.HashMap[Long, LiveInfo]], //"audience"/"anchor"->Map(userId->LiveInfo)
    pullMap: mutable.HashMap[Long, LiveInfo], //uid => pullLiveInfo
    userIdList: mutable.ListBuffer[Long], //存放连线的观众的id 按照前后加入顺序排列
    liker: mutable.Set[Long],
    startTime: Long,
    totalView: Int,
    isJoinOpen: Boolean = false,
    dispatch: WsMsgRm => Unit,
    dispatchTo: (List[(Long, Boolean)], WsMsgRm) => Unit,
    inviteList: mutable.ListBuffer[Long]
  )
    (ctx: ActorContext[Command], userId: Long, roomId: Long, msg: WsMsgClient)
    (
      implicit stashBuffer: StashBuffer[Command],
      timer: TimerScheduler[Command],
      sendBuffer: MiddleBufferInJvm
    ): Behavior[Command] = {
    msg match {
      case ChangeLiveMode(isConnectOpen, aiMode, screenLayout) =>
        val connect = isConnectOpen match {
          case Some(v) => v
          case None => isJoinOpen
        }
        val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
        if (aiMode.isEmpty && screenLayout.nonEmpty) {
          wholeRoomInfo.layout = screenLayout.get
        } else if (aiMode.nonEmpty && screenLayout.isEmpty) {
          wholeRoomInfo.aiMode = aiMode.get
        } else if (aiMode.nonEmpty && screenLayout.nonEmpty) {
          wholeRoomInfo.layout = screenLayout.get
          wholeRoomInfo.aiMode = aiMode.get
        }
        if (!(aiMode.isEmpty && screenLayout.isEmpty)) {
          changeMode(ctx, userId, dispatchTo)(roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
        } else {
          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), ChangeModeRsp())
        }
        idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, connect, inviteList)

      case JoinAccept(roomId, userId4Audience, clientType, accept) =>
        log.debug(s"${ctx.self.path} 接受连线者请求，roomId=$roomId")

        if (accept) {
          //请求的liveinfo的数量 最多连线4人
          val num = if (liveInfoMap.get(Role.audience).isEmpty) 2
          else if (liveInfoMap(Role.audience).size == 1) 4
          else 5

          log.debug(s"申请了$num 个 liveinfo")
          for {
            userInfoOpt <- UserInfoDao.searchById(userId4Audience)
            liveInfoListOp <- RtpClient.getLiveInfoFunc(num)
          } yield {
            liveInfoListOp match {
              case Right(rsp) =>
                log.debug(s"获得liveinfo list size: ${rsp.liveInfo.get.size}")
                if (rsp.liveInfo.get.size < num)
                  dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                else {
                  //获得了足够数量的liveinfo
                  val list = rsp.liveInfo.get
                  if (userInfoOpt.nonEmpty) {

                    //两人连线的情况
                    if (num == 2) {
                      val liveInfo4Client = list.head
                      val liveInfo4Mix = list.last
                      liveInfoMap.get(Role.host) match {
                        case Some(value) =>
                          val liveInfo4Host = value.get(wholeRoomInfo.roomInfo.userId)
                          if (liveInfo4Host.nonEmpty) {
                            //更新liveinfomap和pullMap
                            liveInfoMap.get(Role.audience) match { //将连线观众的liveinfo放入状态中
                              case Some(value4Audience) =>
                                value4Audience.put(userId4Audience, liveInfo4Client)
                                liveInfoMap.put(Role.audience, value4Audience)
                              case None =>
                                liveInfoMap.put(Role.audience, mutable.HashMap(userId4Audience -> liveInfo4Client))
                            }
                            pullMap += (wholeRoomInfo.roomInfo.userId -> liveInfo4Client, userId4Audience -> liveInfo4Host.get)
                            userIdList += userId4Audience
                            liveInfo4Host.foreach { HostLiveInfo =>
                              DistributorClient.startPull(roomId, liveInfo4Mix.liveId) //使用混流的liveid进行推流
                              ProcessorClient.newConnect(roomId, HostLiveInfo.liveId, liveInfo4Client.liveId, liveInfo4Mix.liveId, liveInfo4Mix.liveCode, wholeRoomInfo.layout) //混流
                              ctx.self ! UpdateRTMP(liveInfo4Mix.liveId) //更新wholeRoomInfo
                            }
                            val audienceInfo = AudienceInfo(userId4Audience, userInfoOpt.get.userName, liveInfo4Client.liveId)
                            dispatch(RcvComment(-1l, "", s"user:$userId join in room:$roomId")) //群发评论
                            dispatchTo(subscribers.keys.toList.filter(t => t._1 != wholeRoomInfo.roomInfo.userId && t._1 != userId4Audience), Join4AllRsp(Some(liveInfo4Mix.liveId))) //除了host和连线者发送混流的liveId
                            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinRsp(Some(audienceInfo))) //给主持人发送连线者的username id liveid
                            dispatchTo(List((userId4Audience, false)), JoinRsp(Some(liveInfo4Host.get.liveId), Some(liveInfo4Client))) //给连线者发送主持人的liveid和liveinfo

                          } else {
                            log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)

                          }
                        case None =>
                          log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                      }
                      //三人连线的情况
                    } else if (num == 4) {


                      val liveInfo4HostMix = list.head
                      val liveInfo4ClientMix = list(1)
                      val liveInfo4NewClient = list(2)
                      val liveInfo4Mix = list.last
                      liveInfoMap.get(Role.host) match {
                        case Some(value) =>
                          val liveInfo4Host = value.get(wholeRoomInfo.roomInfo.userId)
                          if (liveInfo4Host.nonEmpty) {
                            //更新liveinfomap和pullMap
                            liveInfoMap(Role.audience).put(userId4Audience, liveInfo4NewClient)
                            log.debug(s"更新之后 liveinfo map size is ${liveInfoMap(Role.audience).size}")
                            //                            pullMap += (wholeRoomInfo.roomInfo.userId -> liveInfo4Client, userId4Audience -> liveInfo4Host.get)
                            val oldMixLiveId = wholeRoomInfo.roomInfo.rtmp
                            oldMixLiveId match {
                              case Some(oldId) =>
                                liveInfo4Host.foreach { HostLiveInfo =>
                                  DistributorClient.startPull(roomId, liveInfo4Mix.liveId) //使用混流的liveid进行推流
                                  //混流主持人拉到的
                                  ProcessorClient.newConnect(roomId, pullMap(wholeRoomInfo.roomInfo.userId).liveId, liveInfo4NewClient.liveId, liveInfo4HostMix.liveId, liveInfo4HostMix.liveCode, wholeRoomInfo.layout) //混流
                                  //混流client1拉到的
                                  ProcessorClient.newConnect(roomId, pullMap(userIdList.head).liveId, liveInfo4NewClient.liveId, liveInfo4ClientMix.liveId, liveInfo4ClientMix.liveCode, wholeRoomInfo.layout) //混流
                                  //混流三人的
                                  ProcessorClient.newConnect(roomId, wholeRoomInfo.roomInfo.rtmp.get, liveInfo4NewClient.liveId, liveInfo4Mix.liveId, liveInfo4Mix.liveCode, wholeRoomInfo.layout) //混流
                                  //更新状态信息
                                  pullMap += (wholeRoomInfo.roomInfo.userId -> liveInfo4HostMix, userIdList.head -> liveInfo4ClientMix, userId4Audience -> LiveInfo(oldId, ""))
                                  userIdList += userId4Audience
                                  ctx.self ! UpdateRTMP(liveInfo4Mix.liveId)
                                }
                                val audienceInfo = AudienceInfo(userId4Audience, userInfoOpt.get.userName, liveInfo4HostMix.liveId)
                                dispatch(RcvComment(-1l, "", s"user:$userId join in room:$roomId")) //群发评论
                                //                                dispatchTo(subscribers.keys.toList.filter(t => t._1 != wholeRoomInfo.roomInfo.userId && t._1 != userId4Audience), Join4AllRsp(Some(liveInfo4Mix.liveId))) //除了host和连线者发送混流的liveId
                                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), NewAudienceJoinRsp(Some(audienceInfo))) //给主持人混流信息的username id liveid
                                dispatchTo(List((userId4Audience, false)), JoinRsp(Some(oldId), Some(liveInfo4NewClient))) //给新连线着发送两人的混流id 以及自己推流的liveinfo
                                dispatchTo(List((userIdList.head, false)), NewJoinRsp(Some(liveInfo4ClientMix.liveId))) //给之前的连线者发送新的混流
                              case None =>
                                log.debug(s"${ctx.self.path} 当前房间没有rtmp,roomId=$roomId")
                                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                            }

                          } else {
                            log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)

                          }
                        case None =>
                          log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
                          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                      }


                    }


                  } else {
                    log.debug(s"${ctx.self.path} 错误的userId,可能是数据库里没有用户,userId=$userId4Audience")
                    dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                  }
                }
              case Left(e) =>
                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            }

            //            (clientLiveInfo, mixLiveInfo) match { //为client和混流申请了两个liveinfo
            //              case (Right(GetLiveInfoRsp(liveInfo4Client, 0, _)), Right(GetLiveInfoRsp(liveInfo4Mix, 0, _))) =>
            //                log.info("client" + liveInfo4Client + "; mix" + liveInfo4Mix)
            //                if (userInfoOpt.nonEmpty) {
            //                  liveInfoMap.get(Role.host) match {
            //                    case Some(value) => //获取主播的liveinfo
            //                      val liveIdHost = value.get(wholeRoomInfo.roomInfo.userId)
            //                      if (liveIdHost.nonEmpty) {
            //                        liveInfoMap.get(Role.audience) match { //将连线观众的liveinfo放入状态中
            //                          case Some(value4Audience) =>
            //                            value4Audience.put(userId4Audience, liveInfo4Client)
            //                            liveInfoMap.put(Role.audience, value4Audience)
            //                          case None =>
            //                            liveInfoMap.put(Role.audience, mutable.HashMap(userId4Audience -> liveInfo4Client))
            //                        }
            //                        liveIdHost.foreach { HostLiveInfo =>
            //                          DistributorClient.startPull(roomId, liveInfo4Mix.liveId) //使用混流的liveid进行推流
            //                          ProcessorClient.newConnect(roomId, HostLiveInfo.liveId, liveInfo4Client.liveId, liveInfo4Mix.liveId, liveInfo4Mix.liveCode, wholeRoomInfo.layout)
            //                          ctx.self ! UpdateRTMP(liveInfo4Mix.liveId)
            //                        }
            //                        //                        val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
            //                        //                        log.debug(s"${ctx.self.path} 更新房间信息，连线者liveIds=${liveList}")
            //                        //                        ProcessorClient.updateRoomInfo(wholeRoomInfo.roomInfo.roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
            //
            //                        val audienceInfo = AudienceInfo(userId4Audience, userInfoOpt.get.userName, liveInfo4Client.liveId)
            //                        dispatch(RcvComment(-1l, "", s"user:$userId join in room:$roomId")) //群发评论
            //                        dispatchTo(subscribers.keys.toList.filter(t => t._1 != wholeRoomInfo.roomInfo.userId && t._1 != userId4Audience), Join4AllRsp(Some(liveInfo4Mix.liveId))) //除了host和连线者发送混流的liveId
            //                        dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinRsp(Some(audienceInfo))) //给主持人发送连线者的username id liveid
            //                        dispatchTo(List((userId4Audience, false)), JoinRsp(Some(liveIdHost.get.liveId), Some(liveInfo4Client))) //给连线者发送主持人的liveid和liveinfo
            //                      } else {
            //                        log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
            //                        dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //
            //                      }
            //                    case None =>
            //                      log.debug(s"${ctx.self.path} 没有主播的liveId,roomId=$roomId")
            //                      dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //                  }
            //                } else {
            //                  log.debug(s"${ctx.self.path} 错误的主播userId,可能是数据库里没有用户,userId=$userId4Audience")
            //                  dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //                }
            //              case (Right(GetLiveInfoRsp(_, errCode, msg)), Right(GetLiveInfoRsp(_, errCode2, msg2))) =>
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Client left error:$errCode msg: $msg")
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Mix left error:$errCode2 msg: $msg2")
            //                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //              case (Left(error1), Left(error2)) =>
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Client left error:$error1")
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Mix left error:$error2")
            //                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //              case (_, Left(error2)) =>
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Client left ok")
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Mix left error:$error2")
            //                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //              case (Left(error1), _) =>
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Client left error:$error1")
            //                log.debug(s"${ctx.self.path.name} join accept get liveInfo4Mix left ok")
            //                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            //            }
          }
        } else {
          dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinRsp(None))
          dispatchTo(List((userId4Audience, false)), JoinRefused)
        }

        Behaviors.same

      case HostShutJoin(roomId) =>

        log.debug(s"${ctx.self.path} the host has shut the join in room$roomId")
        liveInfoMap.remove(Role.audience)
        liveInfoMap.get(Role.host) match {
          case Some(value) =>
            val liveIdHost = value.get(wholeRoomInfo.roomInfo.userId)
            if (liveIdHost.nonEmpty) {
              ctx.self ! UpdateRTMP(liveIdHost.get.liveId)
            }
            else {
              log.debug(s"${ctx.self.path} 没有主播的liveId,无法撤回主播流,roomId=$roomId")
              dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
            }
        }
        ProcessorClient.closeRoom(roomId)
        //        val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
        //        ProcessorClient.updateRoomInfo(wholeRoomInfo.roomInfo.roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
        dispatch(HostDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
        dispatch(RcvComment(-1l, "", s"the host has shut the join in room $roomId"))
        Behaviors.same

      case ModifyRoomInfo(roomName, roomDes) =>

        val roomInfo = if (roomName.nonEmpty && roomDes.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get, roomDes = roomDes.get)
        } else if (roomName.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get)
          wholeRoomInfo.roomInfo.copy(roomName = roomName.get)
        } else if (roomDes.nonEmpty) {
          wholeRoomInfo.roomInfo.copy(roomDes = roomDes.get)
        } else {
          wholeRoomInfo.roomInfo
        }
        val info = WholeRoomInfo(roomInfo, wholeRoomInfo.layout, wholeRoomInfo.aiMode)
        log.debug(s"${ctx.self.path} modify the room info$info")
        dispatch(UpdateRoomInfo2Client(roomInfo.roomName, roomInfo.roomDes))
        dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), ModifyRoomRsp())
        idle(info, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList)


      case HostStopPushStream(roomId) =>
        //val liveId = wholeRoomInfo.roomInfo.rtmp.get
        /* wholeRoomInfo.roomInfo.rtmp match {
           case Some(v) =>
           case None =>
         }*/

        log.debug(s"${ctx.self.path} host stop stream in room${wholeRoomInfo.roomInfo.roomId},name=${wholeRoomInfo.roomInfo.roomName}")
        //前端需要自行处理主播主动断流的情况，后台默认连线者也会断开
        dispatch(HostStopPushStream2Client)
        wholeRoomInfo.roomInfo.rtmp match {
          case Some(v) =>
            if (v != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
              ProcessorClient.closeRoom(roomId)
            log.debug(s"roomId:$roomId 主播停止推流，向distributor发送finishpull消息")
            DistributorClient.finishPull(v)
            if (startTime != -1l) {
              roomManager ! RoomManager.DelaySeekRecord(wholeRoomInfo, totalView, roomId, startTime, v)
            }
          case None =>
        }
        //        if (wholeRoomInfo.roomInfo.rtmp.get != liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId)
        //          ProcessorClient.closeRoom(roomId)
        liveInfoMap.clear()

        val newroomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(rtmp = None, mpd = None))
        log.debug(s"${ctx.self.path} 主播userId=${userId}已经停止推流，更新房间信息，liveId=${newroomInfo.roomInfo.rtmp}")
        subscribers.get((wholeRoomInfo.roomInfo.userId, false)) match {
          case Some(hostActor) =>
            idle(newroomInfo, liveInfoMap, mutable.HashMap((wholeRoomInfo.roomInfo.userId, false) -> hostActor), mutable.HashMap[Long, LiveInfo](), mutable.ListBuffer[Long](), mutable.Set[Long](), -1l, totalView, isJoinOpen, inviteList)
          case None =>
            idle(newroomInfo, liveInfoMap, mutable.HashMap.empty[(Long, Boolean), ActorRef[UserActor.Command]], mutable.HashMap[Long, LiveInfo](), mutable.ListBuffer[Long](), mutable.Set[Long](), -1l, totalView, isJoinOpen, inviteList)
        }

      case JoinReq(userId4Audience, roomId, clientType) =>
        if (isJoinOpen) {
          UserInfoDao.searchById(userId4Audience).map { r =>
            if (r.nonEmpty) {
              //如果在邀请名单中
              if (inviteList.contains(userId4Audience)) {
                val msg = JoinAccept(roomId, userId4Audience, clientType, true)
                ctx.self ! ActorProtocol.WebSocketMsgWithActor(userId, roomId, msg)
              } else
                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoin(userId4Audience, r.get.userName, clientType))
            } else {
              log.debug(s"${ctx.self.path} 连线请求失败，用户id错误id=$userId4Audience in roomId=$roomId")
              dispatchTo(List((userId4Audience, false)), JoinAccountError)
            }
          }.recover {
            case e: Exception =>
              log.debug(s"${ctx.self.path} 连线请求失败，内部错误error=$e")
              dispatchTo(List((userId4Audience, false)), JoinInternalError)
          }
        } else {
          dispatchTo(List((userId4Audience, false)), JoinInvalid)
        }
        Behaviors.same

      case AttendeeSpeakReq(userId4Audience, roomId, clientType) =>

        UserInfoDao.searchById(userId4Audience).map { r =>
          if (r.nonEmpty) {
            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AttendeeSpeak(userId4Audience, r.get.userName, clientType))
          } else {
            log.debug(s"${ctx.self.path} 发言请求失败，用户id错误id=$userId4Audience in roomId=$roomId")
            dispatchTo(List((userId4Audience, false)), JoinAccountError)
          }
        }.recover {
          case e: Exception =>
            log.debug(s"${ctx.self.path} 发言请求失败，内部错误error=$e")
            dispatchTo(List((userId4Audience, false)), JoinInternalError)
        }

        Behaviors.same


      case AudienceShutJoin(roomId) =>
        //todo 切断自己的连线
        liveInfoMap.get(Role.audience) match {
          case Some(value) =>
            log.debug(s"${ctx.self.path} the audience connection has been shut")
            liveInfoMap.remove(Role.audience)
            liveInfoMap.get(Role.host) match {
              case Some(info) =>
                val liveIdHost = info.get(wholeRoomInfo.roomInfo.userId)
                if (liveIdHost.nonEmpty) {
                  ctx.self ! UpdateRTMP(liveIdHost.get.liveId)
                }
                else {
                  log.debug(s"${ctx.self.path} 没有主播的liveId,无法撤回主播流,roomId=$roomId")
                  dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), AudienceJoinError)
                }
              case None =>
                log.debug(s"${ctx.self.path} no host liveId")
            }
            ProcessorClient.closeRoom(roomId)
            //            val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
            //            ProcessorClient.updateRoomInfo(wholeRoomInfo.roomInfo.roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
            dispatch(AuthProtocol.AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
            dispatch(RcvComment(-1l, "", s"the audience has shut the join in room $roomId"))
          case None =>
            log.debug(s"${ctx.self.path} no audience liveId")
        }
        Behaviors.same

      //TODO 暂未使用，暂不修改，版本2019.10.23
      case AudienceShutJoinPlus(userId4Audience) =>
        //切换某个单一用户的连线
        liveInfoMap.get(Role.audience) match {
          case Some(value) =>
            value.get(userId4Audience) match {
              case Some(liveInfo) =>
                log.debug(s"${ctx.self.path} the audience connection has been shut")
                value.remove(userId4Audience)
                liveInfoMap.put(Role.audience, value)
                val liveList = liveInfoMap.toList.sortBy(_._1).flatMap(r => r._2).map(_._2.liveId)
                //ProcessorClient.updateRoomInfo(wholeRoomInfo.roomInfo.roomId, liveList, wholeRoomInfo.layout, wholeRoomInfo.aiMode, 0l)
                dispatch(AudienceDisconnect(liveInfoMap(Role.host)(wholeRoomInfo.roomInfo.userId).liveId))
                dispatch(RcvComment(-1l, "", s"the audience ${userId4Audience} has shut the join in room ${roomId}"))
              case None =>
                log.debug(s"${ctx.self.path} no audience liveId")
            }
          case None =>
            log.debug(s"${ctx.self.path} no audience liveId")
        }
        Behaviors.same


      case LikeRoom(userId, roomId, upDown) =>
        upDown match {
          case Like.up =>
            if (liker.contains(userId)) {
              dispatchTo(List((userId, false)), LikeRoomRsp(1001, "该用户已经点过赞了"))
              Behaviors.same
            } else {
              liker.add(userId)
              val newWholeRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(like = liker.size))
              log.debug(s"${ctx.self.path} 更新房间信息like=${newWholeRoomInfo.roomInfo.like}")
              dispatchTo(List((userId, false)), LikeRoomRsp(0, "点赞成功"))
              dispatch(ReFleshRoomInfo(newWholeRoomInfo.roomInfo))
              idle(newWholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList)
            }
          case Like.down =>
            if (liker.contains(userId)) {
              liker.remove(userId)
              val newWholeRoomInfo = wholeRoomInfo.copy(roomInfo = wholeRoomInfo.roomInfo.copy(like = liker.size))
              log.debug(s"${ctx.self.path} 更新房间信息like=${newWholeRoomInfo.roomInfo.like}")
              dispatchTo(List((userId, false)), LikeRoomRsp(0, "取消点赞成功"))
              dispatch(ReFleshRoomInfo(newWholeRoomInfo.roomInfo))
              idle(newWholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList)
            } else {
              dispatchTo(List((userId, false)), LikeRoomRsp(1002, "该用户还没有点过赞"))
              Behaviors.same
            }
          case _ =>
            Behaviors.same
        }
      //        wholeRoomInfo.roomInfo.like = liker.size
      //        Behaviors.same

      case JudgeLike(userId, roomId) =>
        dispatchTo(List((userId, false)), JudgeLikeRsp(liker.contains(userId)))
        Behaviors.same

      case Comment(userId, roomId, comment, color, extension) =>
        UserInfoDao.searchById(userId).onComplete {
          case Success(value) =>
            value match {
              case Some(v) =>
                dispatch(RcvComment(userId, v.userName, comment, color, extension))
              case None =>
                log.debug(s"${ctx.self.path.name} the database doesn't have the user")
            }
            ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList))
          case Failure(e) =>
            log.debug(s"s${ctx.self.path.name} the search by userId error:$e")
            ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList))
        }
        switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))


      case InviteJoinReq(username) =>

        UserInfoDao.searchByName(username).onComplete {
          case Success(value) =>
            value match {
              case Some(info) =>
                emailActor ! InviteJoin(wholeRoomInfo.roomInfo.userName, info.email, wholeRoomInfo.roomInfo.roomId)
                inviteList += info.uid
                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), InviteJoinRsp())
                ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList))

              case None =>
                dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), UsernameNotExist)
                ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList))

            }

          case Failure(e) =>
            log.debug(s"${ctx.self.path.name} the search bt username error:$e")
            dispatchTo(List((wholeRoomInfo.roomInfo.userId, false)), InviteError)
            ctx.self ! SwitchBehavior("idle", idle(wholeRoomInfo, liveInfoMap, subscribers, pullMap, userIdList, liker, startTime, totalView, isJoinOpen, inviteList))

        }
        switchBehavior(ctx, "busy", busy(), InitTime, TimeOut("busy"))
      case PingPackage =>
        Behaviors.same

      case x =>
        log.debug(s"${ctx.self.path} recv an unknown msg:$x")
        Behaviors.same
    }
  }

  private def changeMode(ctx: ActorContext[RoomActor.Command], anchorUid: Long, dispatchTo: (List[(Long, Boolean)], WsMsgRm) => Unit)(roomId: Long, liveIdList: List[String], screenLayout: Int, aiMode: Int, startTime: Long) = {
    ProcessorClient.updateRoomInfo(roomId, screenLayout).map {
      case Right(rsp) =>
        log.debug(s"${ctx.self.path} modify the mode success")
        dispatchTo(List((anchorUid, false)), ChangeModeRsp())
      case Left(error) =>
        log.debug(s"${ctx.self.path} there is some error:$error")
        dispatchTo(List((anchorUid, false)), ChangeModeError)
    }
  }

  private def dispatch(subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]])(msg: WsMsgRm)(implicit sendBuffer: MiddleBufferInJvm): Unit = {
    log.debug(s"${subscribers}分发消息：$msg")
    subscribers.values.foreach(_ ! UserActor.DispatchMsg(Wrap(msg.asInstanceOf[WsMsgRm].fillMiddleBuffer(sendBuffer).result()), msg.isInstanceOf[AuthProtocol.HostCloseRoom]))
  }

  /**
    * subscribers:所有的订阅者
    * targetUserIdList：要发送的目标用户
    * msg：发送的消息
    **/
  private def dispatchTo(subscribers: mutable.HashMap[(Long, Boolean), ActorRef[UserActor.Command]])(targetUserIdList: List[(Long, Boolean)], msg: WsMsgRm)(implicit sendBuffer: MiddleBufferInJvm): Unit = {
    log.debug(s"${subscribers}定向分发消息：$msg")
    targetUserIdList.foreach { k =>
      subscribers.get(k).foreach(r => r ! UserActor.DispatchMsg(Wrap(msg.asInstanceOf[WsMsgRm].fillMiddleBuffer(sendBuffer).result()), msg.isInstanceOf[AuthProtocol.HostCloseRoom]))
    }
  }


}

package org.seekloud.theia.roomManager.http

import org.seekloud.theia.protocol.ptcl.client2Manager.http.CommonProtocol._
import org.seekloud.theia.roomManager.Boot._

import scala.language.postfixOps
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.seekloud.theia.protocol.ptcl.CommonInfo.RecordInfo
import org.seekloud.theia.protocol.ptcl.CommonRsp
import org.seekloud.theia.protocol.ptcl.client2Manager.http.AdminProtocol.DeleteRecordReq
import org.seekloud.theia.protocol.ptcl.client2Manager.http.StatisticsProtocol
import org.seekloud.theia.roomManager.Boot.{executor, scheduler, userManager}
import org.seekloud.theia.roomManager.models.dao.{AttendDao, RecordDao, RoomDao, StatisticDao, UserInfoDao}
import org.seekloud.theia.roomManager.common.{AppSettings, Common}

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{Failure, Success}

trait RecordService {
  import io.circe._
  import io.circe.syntax._
  import io.circe.generic.auto._


  //目前只根据时间排序
  private val getRecordList = (path("getRecordList") & get) {
    parameters(
      'sortBy.as[String],
      'pageNum.as[Int],
      'pageSize.as[Int],
      'uid.as[Long]
    ) { case (sortBy, pageNum, pageSize, uid) =>

      dealFutureResult {

        def sortRule(record: RecordInfo): Long = {
          record.startTime
        }

        log.debug(s"uid:$uid is getting attend info")
        RoomDao.getAttendRoomid(uid).map { list =>
          //          val recordList = mutable.ListBuffer[RecordInfo]()
          dealFutureResult {
            Future.sequence(list.distinct.map { roomid =>
              RoomDao.searchRecord(roomid).map {
                //
                case (Some(i), Some(info)) =>
                  RecordInfo(i.roomid, i.roomid, i.roomName, i.roomDesc, i.anchorid, info.userName, i.startTime, UserInfoDao.getHeadImg(info.headImg), RoomDao.getCoverImg(i.coverImg), 0, 0, i.duration)

                case _ =>
                  RecordInfo(-1, -1, "", "", -1, "", 0, "", "", 0, 0, "")
              }

            }).map { list =>
              //被时间排序过的list
              val res = list.filter(_.recordId != -1).sortBy(sortRule)(Ordering.Long.reverse).toList
              val slice = res.slice((pageNum - 1) * pageSize, (pageNum - 1) * pageSize + pageSize)
              log.debug(s"recordlist size is ${res.size}")
              complete(GetRecordListRsp(res.size, slice))
            }

          }

        }.recover {
          case e: Exception =>
            log.debug(s"获取录像列表失败：$e")
            complete(GetAuthorRecordListRsp(0, Nil))
        }
      }

    }
  }


  private val searchRecord = (path("searchRecord") & post) {
    entity(as[Either[Error, SearchRecord]]) {
      case Right(req) =>

        dealFutureResult {
          RoomDao.checkAttendByUid(req.userId, req.roomId).map {
            case Some(_) =>
              dealFutureResult {
                RoomDao.searchRecord(req.roomId).map {
                  case (Some(i), Some(anchor)) =>

                    val url = if ((!AppSettings.distributorUseIp)) s"https://${AppSettings.distributorDomain}/theia/distributor/getRecord/${req.roomId}/${req.startTime}/record.mp4"
                    else s"https://${AppSettings.distributorIp}:${AppSettings.distributorPort}/theia/distributor/getRecord/${req.roomId}/${req.startTime}/record.mp4"

                    val recordInfo = RecordInfo(i.roomid, i.roomid, i.roomName, i.roomDesc, i.anchorid, anchor.userName, i.startTime, UserInfoDao.getHeadImg(anchor.headImg), RoomDao.getCoverImg(i.coverImg), 0, 0, i.duration)
                    complete(SearchRecordRsp(url, Some(recordInfo)))


                  case (Some(i), _) =>
                    log.debug(s"未找到该录像roomid:${req.roomId} 主持人信息")
                    complete(AnchorNotExist)

                  case (_, _) =>
                    log.debug(s"未找到该录像roomid:${req.roomId} 信息")
                    complete(RecordNotExist)
                }
              }
            case None =>
              log.debug(s"用户${req.userId} 无权查看 未找到该录像roomid:${req.roomId} 主持人信息")
              complete(NoAuthToWatchRecord)
          }

        }
      case Left(e) =>
        log.debug(s"parse error")
        complete(WatchError)
    }
  }

  //
  //
  //  private val watchRecordOver = (path("watchRecordOver") & post) {
  //    entity(as[Either[Error, StatisticsProtocol.WatchRecordEndReq]]) {
  //      case Right(req) =>
  //        dealFutureResult {
  //          StatisticDao.updateObserveEvent(req.recordId, if (req.userIdOpt.nonEmpty) req.userIdOpt.get else 1l, req.userIdOpt.isEmpty, req.inTime, req.outTime).map { r =>
  //            complete(CommonRsp())
  //          }.recover {
  //            case e: Exception =>
  //              complete(CommonRsp(100046, s"数据库查询错误error=$e"))
  //          }
  //
  //        }
  //      case Left(error) =>
  //        complete(CommonRsp(100045, s"watch over error decode error:$error"))
  //
  //    }
  //  }
  //
  //  private val getAuthorRecordList = (path("getAuthorRecordList") & get) {
  //    parameters(
  //      'roomId.as[Long],
  //    ) { case roomId =>
  //      dealFutureResult {
  //        RecordDao.getAuthorRecordList(roomId).flatMap { recordList =>
  //          log.info("获取主播录像列表成功")
  //          RecordDao.getAuthorRecordTotalNum(roomId).map { n =>
  //            complete(GetAuthorRecordListRsp(n, recordList))
  //          }
  //        }.recover {
  //          case e: Exception =>
  //            log.debug(s"获取录像列表失败：$e")
  //            complete(GetAuthorRecordListRsp(0, Nil))
  //        }
  //      }
  //    }
  //  }

  private val deleteRecord = (path("deleteRecord") & post) {
    entity(as[Either[Error, AuthorDeleteRecordReq]]) {
      case Right(req) =>
        dealFutureResult {
          RoomDao.deleteRecord(req.recordId).map { r =>
            log.info("主播删除录像成功")
            complete(CommonRsp())
          }.recover {
            case e: Exception =>
              complete(CommonRsp(100048, s"主播删除录像id失败，error:$e"))
          }
        }
      case Left(e) => complete(CommonRsp(100048, s"delete author record error: $e"))
    }
  }


  private val inviteWatchRecord = (path("inviteWatchRecord") & post) {
    entity(as[Either[Error, InviteWatchRecordReq]]) {
      case Right(req) =>
        dealFutureResult {

          RoomDao.checkAnchor(req.invitorId, req.roomId).map {
            case None =>
              complete(CommonRsp(1001002, s"用户uid:${req.invitorId} 不是会议发起人 roomid: ${req.roomId}"))
            case _ =>
              dealFutureResult {

                UserInfoDao.searchByName(req.invitee).map {
                  case Some(info) =>
                    dealFutureResult {

                      RoomDao.checkAttend(req.invitee, req.roomId).map {
                        case None =>
                          dealFutureResult {
                            AttendDao.inviteWatchRecord(info.uid, req.roomId).map { _ =>
                              complete(CommonRsp())
                            }
                          }
                        case _ =>
                          complete(CommonRsp(1001003, s"用户 ${req.invitee} 已经有权查看录像 无需邀请"))
                      }
                    }
                  case None =>
                    log.debug(s"用户不存在 username ${req.invitee}")
                    complete(CommonRsp(1001001, s"用户不存在"))
                }

              }
          }
        }
      case Left(e) =>
        log.debug(s"parse error")
        complete(CommonRsp(100100, s"parse error"))
    }
  }


  // 返回该会议邀请信息
  private val getInviteInfo = (path("getInviteInfo") & post) {
    entity(as[Either[Error, GetInviteListReq]]) {
      case Right(req) =>
        dealFutureResult {

          log.debug(s"${req.uid} is getting inviteInfo ")
          RoomDao.checkAnchor(req.uid, req.roomid).map {
            case None =>
              complete(GetInviteeListRsp(errCode = 100111, msg = s"用户uid${req.uid}不是会议发起人，无权查看"))
            case _ =>
              dealFutureResult {
                RoomDao.getInviteeInfo(req.roomid).map { list =>

                  log.debug(s"list size 1111111111 is ${list.size}")
                  dealFutureResult {

                    Future.sequence {
                      list.map { uid =>
                        UserInfoDao.searchById(uid).map {
                          case Some(i) =>
                            InviteeInfo(i.uid, i.userName, i.headImg)
                          case _ =>
                            InviteeInfo(-1, "", "")
                        }
                      }.toList
                    }.map { list =>
                      val res = list.filter(_.uid != -1)
                      log.debug(s"list size is ${res.size}")
                      complete(GetInviteeListRsp(res))
                    }
                  }
                }
              }
          }
        }
      case Left(ex) =>
        log.debug(s"parse error")
        complete(GetInviteeListRsp(errCode = 100110, msg = "parse error"))
    }
  }


  private val deleteWatchInvite = (path("deleteWatchInvite") & post) {

    entity(as[Either[Error, DeleteWatchInviteReq]]) {
      case Right(req) =>
        dealFutureResult {
          log.debug(s"${req.uid} is delete inviteInfo ")
          RoomDao.checkAnchor(req.uid, req.roomId).map {
            case None =>
              complete(CommonRsp(100202, s"用户uid:${req.uid}不是会议发起人，无权查看"))
            case _ =>
              dealFutureResult {
                RoomDao.checkInviteeOfRecord(req.invitee, req.roomId).map {

                  case Some(info) =>

                    dealFutureResult {
                      RoomDao.deleteInviteOfRecord(info.id).map { _ =>
                        complete(CommonRsp())
                      }
                    }
                  case None =>
                    log.debug(s"该用户uid: ${req.invitee} 未被邀请该会议roomid:${req.roomId}")
                    complete(CommonRsp(100201, s"该用户uid: ${req.invitee} 未被邀请该会议roomid:${req.roomId}"))
                }
              }


          }

        }
      case Left(e) =>
        log.debug(s"parse error")
        complete(CommonRsp(100200, s"parse error"))
    }

  }


  val recordRoutes: Route = pathPrefix("record") {
    getRecordList ~ searchRecord ~ deleteRecord ~ inviteWatchRecord ~ deleteWatchInvite ~ getInviteInfo
  }
}

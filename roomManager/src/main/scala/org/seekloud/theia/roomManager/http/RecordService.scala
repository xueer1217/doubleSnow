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
import org.seekloud.theia.roomManager.models.dao.{RecordDao, RoomDao, StatisticDao, UserInfoDao}
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


        RoomDao.getAttendRoomid(uid).map { list =>
          val recordList = mutable.ListBuffer[RecordInfo]()
          list.distinct.map { roomid =>

            RoomDao.searchRecord(roomid).map { res =>

              val (record, anchor) = res

              if (record.nonEmpty && anchor.nonEmpty && record.get.duration.nonEmpty) {

                val i = record.get
                val info = anchor.get
                recordList += RecordInfo(i.roomid, i.roomid, i.roomName, i.roomDesc, i.anchorid, info.userName, i.startTime, UserInfoDao.getHeadImg(info.headImg), RoomDao.getCoverImg(i.coverImg), 0, 0, i.duration)

              }
            }
          }

          def sortRule(record: RecordInfo): Long = {
            record.startTime
          }

          val sortedRecord = recordList.sortBy(sortRule)(Ordering.Long.reverse).toList



          val res = sortedRecord.slice((pageNum - 1) * pageSize, (pageNum - 1) * pageSize + pageSize)


          complete(GetRecordListRsp(sortedRecord.size, res))
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
          RoomDao.searchRecord(req.roomId).map {
            case (Some(i), Some(anchor)) =>
              //              dealFutureResult {

              //                StatisticDao.addObserveEvent(if (req.userIdOpt.nonEmpty) req.userIdOpt.get else 1l, recordInfo.recordId, false, req.userIdOpt.isEmpty, req.inTime).map { r =>
              //                  RecordDao.updateViewNum(req.roomId, req.startTime, recordInfo.observeNum + 1)
              val url = s"https://${AppSettings.distributorDomain}/theia/distributor/getRecord/${req.roomId}/${req.startTime}/record.mp4"
              val recordInfo = RecordInfo(i.roomid, i.roomid, i.roomName, i.roomDesc, i.anchorid, anchor.userName, i.startTime, UserInfoDao.getHeadImg(anchor.headImg), RoomDao.getCoverImg(i.coverImg), 0, 0, i.duration)
              complete(SearchRecordRsp(url, Some(recordInfo)))

            //                }
            //              }

            case (Some(i), _) =>
              complete(AnchorNotExist)
            case (_, _) =>
              complete(RecordNotExist)
          }
        }
      case Left(e) =>
        complete(CommonRsp(100070, s"parse error:$e"))
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
                            RoomDao.inviteWatchRecord(info.uid, req.roomId).map { _ =>
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

          RoomDao.checkAnchor(req.uid, req.roomid).map {
            case None =>
              complete(GetInviteeListRsp(errCode = 100111, msg = s"用户uid${req.uid}不是会议发起人，无权查看"))
            case _ =>
              dealFutureResult {


                RoomDao.getInviteeInfo(req.roomid).map { list =>
                  val inviteeList = mutable.ListBuffer[InviteeInfo]()
                  list.map { uid =>
                    UserInfoDao.searchById(uid).map{
                      case Some(i) =>
                        inviteeList +=   InviteeInfo(i.uid, i.userName, i.headImg)
                      case _ =>
                    }
                  }

                  complete(GetInviteeListRsp(inviteeList.toList))
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
          RoomDao.checkAnchor(req.uid, req.roomId).map {
            case None =>
              complete(CommonRsp(100202, s"用户uid:${req.uid}不是会议发起人，无权查看"))
            case _ =>
              dealFutureResult {
                RoomDao.findInviteeOfRecord(req.invitee, req.roomId).map {

                  case Some(info) =>

                    dealFutureResult {
                      RoomDao.deleteInviteOfRecord(info.id).map { _ =>
                        complete(CommonRsp())
                      }
                    }
                  case None =>
                    log.debug(s"该用户uid: ${req.invitee} 无权访问该会议roomid:${req.roomId}")
                    complete(CommonRsp(100201, s"该用户uid: ${req.invitee} 无权访问该会议roomid:${req.roomId}"))
                }
              }


          }

        }
      case Left(e) =>
        log.debug(s"parse error")
        complete(CommonRsp(100200, s"parse error"))
    }

  }

  //  private val addRecordAddr = (path("addRecordAddr") & post) {
  //    entity(as[Either[Error, AddRecordAddrReq]]) {
  //      case Right(req) =>
  //        dealFutureResult {
  //          RecordDao.addRecordAddr(req.recordId, req.recordAddr).map { r =>
  //            if (r == 1) {
  //              log.info("添加录像地址成功")
  //              complete(CommonRsp())
  //            } else {
  //              log.info("添加录像地址失败")
  //              complete(CommonRsp(100049, s"add record failed"))
  //            }
  //          }
  //        }
  //      case Left(e) =>
  //        complete(CommonRsp(100050, s"add record req error: $e"))
  //    }
  //  }


  val recordRoutes: Route = pathPrefix("record") {
    getRecordList ~ searchRecord ~ deleteRecord ~ inviteWatchRecord ~ deleteWatchInvite ~ getInviteInfo
  }
}

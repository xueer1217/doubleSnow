package org.seekloud.theia.roomManager.http

import org.seekloud.theia.roomManager.Boot._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import org.seekloud.theia.protocol.ptcl.CommonRsp
import org.seekloud.theia.protocol.ptcl.client2Manager.http.RecordCommentProtocol
import org.seekloud.theia.protocol.ptcl.client2Manager.http.RecordCommentProtocol.{CommentInfo, DeleteCommentReq}
import org.seekloud.theia.roomManager.models.dao.{RecordCommentDAO, RecordDao, RoomDao, UserInfoDao}
import org.seekloud.theia.roomManager.models.SlickTables._

/**
  * created by benyafang on 2019/9/23 13:12
  **/

trait RecordCommentService extends ServiceUtils {
  import io.circe._
  import io.circe.syntax._
  import io.circe.generic.auto._

  private def addRecordCommentErrorRsp(msg: String) = CommonRsp(100012, msg)

  private val addRecordComment = (path("addRecordComment") & post) {
    entity(as[Either[Error, RecordCommentProtocol.AddRecordCommentReq]]) {
      case Right(req) =>
        dealFutureResult {
          UserInfoDao.searchById(req.commentUid).map {
            case Some(v) =>
              if (v.`sealed`) {
                complete(addRecordCommentErrorRsp(s"该用户已经被封号，无法评论"))
              } else {
                dealFutureResult {


                  RoomDao.searchRecord(req.roomId).map { v =>
                    val (record, anchor) = v
                    record match {
                      case Some(_) =>
                        dealFutureResult {
                          RecordCommentDAO.addRecordComment(rRecordComment(RecordCommentDAO.id.getAndIncrement(),req.roomId, req.recordTime, req.comment, req.commentTime, req.commentUid, req.authorUidOpt, req.relativeTime)).map { r =>
                            complete(CommonRsp())
                          }.recover {
                            case e: Exception =>
                              log.debug(s"增加记录评论失败，error=$e")
                              complete(addRecordCommentErrorRsp(s"增加记录评论失败，error=$e"))
                          }
                        }
                      case None =>
                        log.debug(s"增加记录评论失败，录像不存在")
                        complete(addRecordCommentErrorRsp(s"增加记录评论失败，录像不存在"))
                    }
                  }
                }

              }
            case None =>
              complete(addRecordCommentErrorRsp(s"无法找到该用户"))
          }
        }
      case Left(error) =>
        log.debug(s"增加记录评论失败，请求错误，error=$error")
        complete(addRecordCommentErrorRsp(s"增加记录评论失败，请求错误，error=$error"))
    }

  }


  private def getRecordCommentListErrorRsp(msg: String) = CommonRsp(1000034, msg)

  //未修改
  private val getRecordCommentList = (path("getRecordCommentList") & post) {
    //    authUser{ _ =>
    entity(as[Either[Error, RecordCommentProtocol.GetRecordCommentListReq]]) {
      case Right(req) =>
        dealFutureResult {
          RecordCommentDAO.getRecordComment(req.roomId).flatMap { ls =>

            val userInfoList = (ls.map { r => r.commentUid } ++ ls.map(_.authorUid).filter(_.nonEmpty).map(_.get)).toSet.toList
            UserInfoDao.getUserDes(userInfoList).map { userInfoLs =>
              val resLs = ls.map { r =>
                val commentUserInfo = userInfoLs.find(_.userId == r.commentUid)
                commentUserInfo match {
                  case Some(userInfo) =>
                    r.authorUid match {
                      case Some(uid) =>
                        userInfoLs.find(_.userId == uid) match {
                          case Some(authorUerInfo) =>
                            CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, userInfo.userName, userInfo.headImgUrl, r.authorUid, Some(authorUerInfo.userName), Some(authorUerInfo.headImgUrl))

                          case None =>
                            CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, userInfo.userName, userInfo.headImgUrl)

                        }
                      case None =>
                        CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, userInfo.userName, userInfo.headImgUrl, r.authorUid)

                    }
                  case None =>
                    r.authorUid match {
                      case Some(uid) =>
                        userInfoLs.find(_.userId == uid) match {
                          case Some(authorUerInfo) =>
                            CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, "", "", r.authorUid, Some(authorUerInfo.userName), Some(authorUerInfo.headImgUrl))

                          case None =>
                            CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, "", "", r.authorUid)

                        }
                      case None =>
                        CommentInfo(r.id, r.roomId, r.recordTime, r.comment, r.commentTime, r.relativeTime, r.commentUid, "", "", r.authorUid)
                    }

                }
              }
              complete(RecordCommentProtocol.GetRecordCommentListRsp(resLs.toList))
            }.recover {
              case e: Exception =>
                log.debug(s"获取评论列表失败，recover error:$e")
                complete(getRecordCommentListErrorRsp(s"获取评论列表失败，recover error:$e"))
            }
          }.recover {
            case e: Exception =>
              log.debug(s"获取评论列表失败，recover error:$e")
              complete(getRecordCommentListErrorRsp(s"获取评论列表失败，recover error:$e"))
          }
        }

      case Left(error) =>
        log.debug(s"获取评论列表失败，请求错误，error=$error")
        complete(getRecordCommentListErrorRsp(s"获取评论列表失败，请求错误，error=$error"))
    }
    //    }
  }

  private val  deleteComment = (path("deleteComment")&post){
    entity(as[Either[Error,DeleteCommentReq]]){
      case Right(req) =>
        dealFutureResult{
            RoomDao.checkAnchor(req.uid,req.roomid).map{
              case None =>
                log.debug(s"uid:${req.uid} 不是会议发起人无权删除评论")
                complete(CommonRsp(500101,s"uid:${req.uid} 不是会议发起人无权删除评论"))
              case Some(_) =>
                dealFutureResult{
                  RecordCommentDAO.deleteRecordComment(req.commentid).map{ _=>
                    complete(CommonRsp())
                  }
                }
            }
        }
      case Left(ex) =>
        log.debug(s"parse error")
        complete(CommonRsp(500100,"parse error"))
    }
  }




  val recordComment = pathPrefix("recordComment") {
    addRecordComment ~ getRecordCommentList ~ deleteComment

  }
}

package org.seekloud.theia.roomManager.models.dao

import java.util.concurrent.atomic.AtomicLong

import org.seekloud.theia.roomManager.Boot.executor
import org.seekloud.theia.roomManager.common.Common
import org.seekloud.theia.roomManager.utils.DBUtil._
import org.seekloud.theia.roomManager.models.SlickTables._
import org.slf4j.LoggerFactory
import slick.jdbc.H2Profile.api._

import scala.util.{Failure, Success}

/**
  * User: haoxue
  * Date: 2020/02/07
  * Time: 14：16
  * Description: 对room的一些数据库操作
  */
object RoomDao {

  val roomid = new AtomicLong(4000001)

  def getCoverImg(coverImg: String): String = {
    if (coverImg == "") Common.DefaultImg.coverImg else coverImg
  }

  def createRoom(uid: Long) = {
    val q = for {
      username <- tUserInfo.filter(_.uid === uid).map(_.userName).result.head
      res <- tRoom.returning(tRoom.map(_.roomid)) += rRoom(roomid.getAndIncrement(), username + "的会议", "暂无会议描述", "", System.currentTimeMillis(), uid, "")
    } yield res

    db.run(q).andThen{
      case Success(_) =>
        log.debug("数据表Room操作成功！！")
      case Failure(ex) =>
        ex.printStackTrace()
    }
  }

  def getRoomInfo(roomId: Long) = {
    db.run(tRoom.filter(_.roomid === roomId).result.headOption)
  }

  def updateDuration(roomId: Long, duration: String) = {
    db.run(tRoom.filter(_.roomid === roomId).map(_.duration).update(duration))
  }

  //获取用户参与的会议idList

  def getAttendRoomid(uid: Long) = {
    db.run(tAttendEvent.filter(_.uid === uid).map(_.roomid).result)
  }


  def searchRecord(roomId: Long) = {
    val q = for {
      record <-  tRoom.filter(i=> i.roomid === roomId && i.duration =!= "").result.headOption
      anchorId <- tRoom.filter(_.roomid === roomId).map(_.anchorid).result.headOption
      anchor <- tUserInfo.filter(_.uid === anchorId.getOrElse(0L)).result.headOption
    } yield {
      (record, anchor)
    }
    db.run(q)
  }

  def deleteRecord(roomid: Long) = {

    db.run(tRoom.filter(_.roomid === roomid).delete)

  }

  //验证该用户是否为会议发起人
  def checkAnchor(uid: Long, roomId: Long) = {
    db.run(tRoom.filter(i => i.roomid === roomId && i.anchorid === uid).result.headOption)
  }

  //用户名验证该用户是否已经有资格查看录像
  def checkAttend(username: String, roomId: Long) = {
    val q = for {
      uid <- tUserInfo.filter(_.userName === username).map(_.uid).result.headOption
      f <- tAttendEvent.filter(i => i.uid === uid.getOrElse(0L) && i.roomid === roomId).result.headOption
    } yield {
      f
    }

    db.run(q)

  }
  def checkAttendByUid(uid:Long,roomid:Long) = {
    db.run(tAttendEvent.filter(i => i.uid === uid && i.roomid === roomid).result.headOption)
  }




  def inviteWatchRecord(uid: Long, roomid: Long) = {

    db.run(tAttendEvent += rAttendEvent(0L, uid, roomid, 0L, 0L))

  }

  //确认该用户是否被邀请看录像
  def checkInviteeOfRecord(uid: Long, roomId: Long) = {
    db.run(tAttendEvent.filter(i => i.uid === uid && i.roomid === roomId && i.inTime === 0L).result.headOption)
  }

  //删除邀请
  def deleteInviteOfRecord(id: Long) = {
    db.run(tAttendEvent.filter(_.id === id).delete)
  }

  //获取邀请列表
  def getInviteeInfo(roomId: Long) = {

    db.run(tAttendEvent.filter(i => i.roomid === roomId && i.inTime === 0L).map(_.uid).result)
  }


}

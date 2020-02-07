package org.seekloud.theia.roomManager.models.dao

import org.seekloud.theia.roomManager.Boot.executor
import org.seekloud.theia.roomManager.common.Common
import org.seekloud.theia.roomManager.utils.DBUtil._
import org.seekloud.theia.roomManager.models.SlickTables._
import slick.jdbc.H2Profile.api._

/**
  * User: haoxue
  * Date: 2020/02/07
  * Time: 14：16
  * Description: 对room的一些数据库操作
  */
object RoomDao {


  def getCoverImg(coverImg:String):String = {
    if(coverImg == "")Common.DefaultImg.coverImg else coverImg
  }

  def createRoom(uid: Long) = {
    val q = for {
      username <- tUserInfo.filter(_.uid === uid).map(_.userName).result.head
      res <- tRoom.returning(tRoom.map(_.roomid)) += rRoom(0L, username + "的会议", "暂无会议描述", "", System.currentTimeMillis(), 0, uid)
    } yield res

    db.run(q)
  }

  def getRoomInfo(roomId:Long)={
    db.run(tRoom.filter(_.roomid===roomId).result.headOption)
  }


}

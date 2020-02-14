package org.seekloud.theia.roomManager.models.dao

import java.util.concurrent.atomic.AtomicLong

import org.seekloud.theia.roomManager.Boot.executor
import org.seekloud.theia.roomManager.utils.DBUtil._
import org.seekloud.theia.roomManager.models.SlickTables._
import slick.jdbc.H2Profile.api._

/**
  * User: haoxue
  * Date: 2020/02/07
  * Time: 14：16
  * Description: 对attend_event的一些数据库操作
  */
object AttendDao {


  val id = new AtomicLong(3000001)

  def addAttendEvent(uid:Long,roomId:Long,inTime:Long) ={
    db.run(tAttendEvent += rAttendEvent(id.getAndIncrement(),uid,roomId,inTime,0))
  }


}
